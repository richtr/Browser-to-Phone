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

var apiVersion = 5;
var baseUrl = 'https://browsetophone.appspot.com';
var sendUrl = baseUrl + '/send?ver=' + apiVersion;
var signInUrl = baseUrl + '/signin?extret=' +
    encodeURIComponent(chrome.extension.getURL('help.html')) + '%23signed_in&ver=' + apiVersion;
var signOutUrl = baseUrl + '/signout?extret=' +
    encodeURIComponent(chrome.extension.getURL('signed_out.html')) + '&ver=' + apiVersion;
var registerUrl =  baseUrl + '/register?ver=' + apiVersion;

var STATUS_SUCCESS = 'success';
var STATUS_LOGIN_REQUIRED = 'login_required';
var STATUS_DEVICE_NOT_REGISTERED = 'device_not_registered';
var STATUS_GENERAL_ERROR = 'general_error';

var channel;
var socket;
var req = new XMLHttpRequest();

function sendToPhone(title, url, msgType, selection, listener) {
  req.open('POST', sendUrl, true);
  req.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
  req.setRequestHeader('X-Same-Domain', 'true');  // XSRF protector

  req.onreadystatechange = function() {
    if (this.readyState == 4) {
      if (req.status == 200) {
        var body = req.responseText;
        if (body.indexOf('OK') == 0) {
          listener(STATUS_SUCCESS);
        } else if (body.indexOf('LOGIN_REQUIRED') == 0) {
          listener(STATUS_LOGIN_REQUIRED);
        } else if (body.indexOf('DEVICE_NOT_REGISTERED') == 0) {
          listener(STATUS_DEVICE_NOT_REGISTERED);
        }
      } else {
        listener(STATUS_GENERAL_ERROR);
      }
    }
  };

  var data = 'title=' + encodeURIComponent(title) + '&url=' + encodeURIComponent(url) +
      '&sel=' + encodeURIComponent(selection) + '&type=' + encodeURIComponent(msgType) +
      '&deviceType=ac2dm';
  req.send(data);
}

function initializeBrowserChannel() {
  console.log('Initializing browser channel');

  var deviceRegistrationId = localStorage['deviceRegistrationId'];
  if (deviceRegistrationId == undefined) {
    deviceRegistrationId = (Math.random() + '').substring(3);
    localStorage['deviceRegistrationId'] = deviceRegistrationId;
  }

  req.open('POST', registerUrl, true);
  req.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
  req.setRequestHeader('X-Same-Domain', 'true');  // XSRF protector

  req.onreadystatechange = function() {
    if (this.readyState == 4) {
      if (req.status == 200) {
        var channelId = req.responseText.substring(3);  // expect 'OK <id>';
        channel = new goog.appengine.Channel(channelId);
        socket = channel.open();
        socket.onopen = function() {
          console.log('Browser channel initialized');
        }
        socket.onclose = function() {
          console.log('Browser channel closed');
          setTimeout('initializeBrowserChannel()', 0); 
        }
        socket.onerror = function(error) {
          if (error.code == 401) {  // token expiry
            console.log('Browser channel token expired - reconnecting');
          } else {
            console.log('Browser channel error');
            // Automatically reconnects
          }
        }
        socket.onmessage = function(evt) {
          var url = unescape(evt.data);
          var regex = /http[s]?:\/\//;
          if (regex.test(url)) { 
            chrome.tabs.create({url: url})
          }
        }
      } else if (req.status == 400) {
        if (req.responseText.indexOf('LOGIN_REQUIRED') == 0) {
          console.log('Not initializing browser channel because user not logged in');
        } else if (req.responseText.indexOf('NOT_ENABLED') == 0) {
          console.log('Not initializing browser channel because feature not enabled for user');
        } 
      }
    }
  };
  var data = 'devregid=' + deviceRegistrationId + '&deviceId=' + deviceRegistrationId +
      '&deviceType=chrome' + '&deviceName=Browser';
  req.send(data);
}

