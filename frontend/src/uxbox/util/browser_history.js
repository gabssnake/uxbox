/**
 * intervaltree
 *
 * @author Andrey Antukh <niwi@niwi.nz>, 2016
 * @license MIT License <https://opensource.org/licenses/MIT>
 */

"use strict";

goog.provide("uxbox.util.browser_history");
goog.require("goog.history.Html5History");


goog.scope(function() {
  const self = uxbox.util.browser_history;
  const Html5History = goog.history.Html5History;

  class TokenTransformer {
    retrieveToken(pathPrefix, location) {
      return location.pathname.substr(pathPrefix.length) + location.search;
    }

    createUrl(token, pathPrefix, location) {
      return pathPrefix + token;
    }
  }

  self.create = function() {
    const instance = new Html5History(null, new TokenTransformer());
    instance.setUseFragment(true);
    return instance;
  };

  self.enable_BANG_ = function(instance) {
    instance.setEnabled(true);
  };

  self.disable_BANG_ = function(instance) {
    instance.setEnabled(false);
  };

  self.set_token_BANG_ = function(instance, token) {
    instance.setToken(token);
  }

  self.replace_token_BANG_ = function(instance, token) {
    instance.replaceToken(token);
  }
});
