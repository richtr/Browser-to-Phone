/*
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.browsertophone.server;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

import javax.jdo.PersistenceManager;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.browsertophone.c2dm.server.C2DMessaging;
import com.google.appengine.api.channel.ChannelMessage;
import com.google.appengine.api.channel.ChannelServiceFactory;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.users.User;

@SuppressWarnings("serial")
public class SendServlet extends HttpServlet {
    private static final Logger log =
        Logger.getLogger(SendServlet.class.getName());
    private static final String OK_STATUS = "OK";
    private static final String LOGIN_REQUIRED_STATUS = "LOGIN_REQUIRED";
    private static final String DEVICE_NOT_REGISTERED_STATUS = "DEVICE_NOT_REGISTERED";
    private static final String ERROR_STATUS = "ERROR";

    // GET not supported

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain");

        // Basic XSRF protection (TODO: remove X-Extension in a future release for consistency)
        if (req.getHeader("X-Same-Domain") == null && req.getHeader("X-Extension") == null) {
            resp.setStatus(400);
            resp.getWriter().println(ERROR_STATUS + " (Missing header)");
            log.warning("Missing header");
            return;
        }

        String sel = req.getParameter("sel");
        if (sel == null) sel = "";  // optional

        String title = req.getParameter("title");
        if (title == null) title = "";  // optional

        String url = req.getParameter("url");
        if (url == null) {
            resp.setStatus(400);
            resp.getWriter().println(ERROR_STATUS + " (Must specify url parameter)");
            return;
        }

        String deviceName = req.getParameter("deviceName");
        String deviceType = req.getParameter("deviceType");

        User user = RegisterServlet.checkUser(req, resp, false);
        if (user != null) {
            doSendToDevice(url, title, sel, user.getEmail(),
                    deviceName, deviceType, resp);
        } else {
            resp.getWriter().println(LOGIN_REQUIRED_STATUS);
        }
    }

    private boolean doSendToDevice(String url, String title, String sel, String userAccount,
            String deviceName, String deviceType, HttpServletResponse resp) throws IOException {

        // ok = we sent to at least one device.
        boolean ok = false;

        // Send push message to phone
        C2DMessaging push = C2DMessaging.get(getServletContext());
        boolean res = false;

        String collapseKey = "" + url.hashCode();

        PersistenceManager pm =
            C2DMessaging.getPMF(getServletContext()).getPersistenceManager();
        
        // delete will fail if the pm is different than the one used to 
        // load the object - we must close the object when we're done
        
        List<DeviceInfo> registrations = null;
        try {
            registrations = DeviceInfo.getDeviceInfoForUser(pm, userAccount);

            // Deal with upgrades and multi-device:
            // If user has one device with an old version and few new ones - 
            // the old registration will be deleted. 
            if (registrations.size() > 1) {
                // Make sure there is no 'bare' registration
                // Keys are sorted - check the first
                DeviceInfo first = registrations.get(0);
                Key oldKey = first.getKey();
                if (oldKey.toString().indexOf("#") < 0) {
                    log.warning("Removing old-style key " + oldKey.toString());
                    // multiple devices, first is old-style.
                    registrations.remove(0); // don't send to it
                    pm.deletePersistent(first);
                }
            }

            int numSendAttempts = 0;
            for (DeviceInfo deviceInfo : registrations) {
                if (deviceName != null && !deviceName.equals(deviceInfo.getName())) {
                    continue;  // user-specified device name
                }
                if (deviceType != null && !deviceType.equals(deviceInfo.getType())) {
                    continue;  // user-specified device type
                }

                try {
                    if (deviceInfo.getType().equals(DeviceInfo.TYPE_CHROME)) {
                        res = doSendViaBrowserChannel(url, deviceInfo);
                    } else {
                        res = doSendViaC2dm(url, title, sel, push, collapseKey, deviceInfo);
                    }
                    numSendAttempts++;

                    if (res) {
                        log.info("Link sent to phone! collapse_key:" + collapseKey);
                        ok = true;
                    } else {
                        log.warning("Error: Unable to send link to phone.");
                    }
                } catch (IOException ex) {
                    if ("NotRegistered".equals(ex.getMessage()) ||
                            "InvalidRegistration".equals(ex.getMessage())) {
                        // Prune device, it no longer works
                        pm.deletePersistent(deviceInfo);
                    } else {
                        throw ex;
                    }
                }
            }

            if (ok) {
                resp.getWriter().println(OK_STATUS);
                return true;
            } else if (numSendAttempts == 0) {
                log.warning("Device not registered " + userAccount);
                resp.getWriter().println(DEVICE_NOT_REGISTERED_STATUS);
                return false;
            } else {
                resp.setStatus(500);
                resp.getWriter().println(ERROR_STATUS + " (Unable to send link)");
                return false;
            }
        } finally {
            pm.close();
        }
    }

    boolean doSendViaC2dm(String url, String title, String sel, C2DMessaging push,
            String collapseKey, DeviceInfo deviceInfo) throws IOException {

        // Trim title, sel if needed.
        if (url.length() + title.length() + sel.length() > 1000) {
            // Shorten the title - C2DM has a 1024 limit, some padding for keys
            if (title.length() > 16) {
                title = title.substring(0, 16);
            }
            // still not enough ?
            if (title.length() + url.length() + sel.length() > 1000) {
                // how much space we have for sel ?
                int space = 1000 - url.length() - title.length();
                if (space > 0 && sel.length() > space) {
                    sel = sel.substring(0, space);
                } // else: we'll get an error sending
            }
            // TODO: when we have history, save the url/title/sel in the history
            // and send a pointer, have device fetch it.
        }

        boolean res;
        res = push.sendNoRetry(deviceInfo.getDeviceRegistrationID(),
                collapseKey,
                "url", url,
                "title", title,
                "sel", sel,
                "debug", deviceInfo.getDebug() ? "1" : null);
        return res;
    }

    boolean doSendViaBrowserChannel(String url, DeviceInfo deviceInfo) {
        String channelToken = deviceInfo.getDeviceRegistrationID();
        ChannelServiceFactory.getChannelService().sendMessage(
                new ChannelMessage(channelToken, url));
        return true;
    }
}
