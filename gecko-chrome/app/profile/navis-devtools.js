/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Page-tool defaults selected from the pinned ESR Firefox profile. Keep this
// file with the DevTools capability so builds that omit DevTools do not retain
// dormant product policy. Review the complete block on every ESR transition.

// Navis exposes page targets through its bounded Runtime command. It does not
// expose Browser Toolbox or a remotely reachable parent-process debugger.
pref("devtools.chrome.enabled", false, locked);
pref("devtools.debugger.remote-enabled", false, locked);
pref("devtools.debugger.force-local", true, locked);

// Toolbox and docking.
pref("devtools.toolbox.footer.height", 250);
pref("devtools.toolbox.sidebar.width", 500);
pref("devtools.toolbox.host", "bottom");
pref("devtools.toolbox.previousHost", "right");
pref("devtools.toolbox.selectedTool", "inspector");
pref("devtools.toolbox.zoomValue", "1");
pref("devtools.toolbox.splitconsole.enabled", true);
pref("devtools.toolbox.splitconsole.open", false);
pref("devtools.toolbox.splitconsoleHeight", 100);
pref("devtools.toolbox.tabsOrder", "");
pref("devtools.toolbox.alwaysOnTop", true);
pref("devtools.popups.debug", false);

// Toolbox buttons.
pref("devtools.command-button-pick.enabled", true);
pref("devtools.command-button-frames.enabled", true);
pref("devtools.command-button-responsive.enabled", true);
pref("devtools.command-button-screenshot.enabled", false);
pref("devtools.command-button-rulers.enabled", false);
pref("devtools.command-button-measure.enabled", false);
pref("devtools.command-button-noautohide.enabled", false);
pref("devtools.command-button-errorcount.enabled", true);
pref("devtools.command-button-jstracer.enabled", false);

// Inspector, markup, layout and highlighters.
pref("devtools.inspector.enabled", true);
pref("devtools.inspector.selectedSidebar", "layoutview");
pref("devtools.inspector.activeSidebar", "layoutview");
pref("devtools.inspector.three-pane-enabled", true);
pref("devtools.inspector.chrome.three-pane-enabled", false);
pref("devtools.inspector.show_pseudo_elements", false);
pref("devtools.inspector.imagePreviewTooltipSize", 300);
pref("devtools.inspector.showUserAgentStyles", false);
pref("devtools.inspector.showAllAnonymousContent", false);
pref("devtools.inspector.simple-highlighters-reduced-motion", false);
pref("devtools.inspector.rule-view.focusNextOnEnter", true);
pref("devtools.inspector.css-explainers", false);
pref("devtools.inspector.draggable_properties", true);
pref("devtools.overflow.debugging.enabled", true);
pref("devtools.gridinspector.gridOutlineMaxColumns", 50);
pref("devtools.gridinspector.gridOutlineMaxRows", 50);
pref("devtools.gridinspector.showGridAreas", false);
pref("devtools.gridinspector.showGridLineNumbers", false);
pref("devtools.gridinspector.showInfiniteLines", false);
pref("devtools.gridinspector.maxHighlighters", 3);
pref("devtools.layout.boxmodel.opened", true);
pref("devtools.layout.flexbox.opened", true);
pref("devtools.layout.flex-container.opened", true);
pref("devtools.layout.flex-item.opened", true);
pref("devtools.layout.grid.opened", true);
pref("devtools.layout.boxmodel.highlightProperty", false);
pref("devtools.eyedropper.zoom", 6);
pref("devtools.markup.collapseAttributes", true);
pref("devtools.markup.collapseAttributeLength", 120);
pref("devtools.markup.showComments", true);
pref("devtools.markup.beautifyOnCopy", false);

// Page tools and feature panels.
pref("devtools.memory.enabled", true);
pref("devtools.memory.custom-census-displays", "{}");
pref("devtools.memory.custom-label-displays", "{}");
pref("devtools.memory.custom-tree-map-displays", "{}");
pref("devtools.memory.max-individuals", 1000);
pref("devtools.memory.max-retaining-paths", 10);
pref("devtools.performance.enabled", true);
pref("devtools.cache.disabled", false);
pref("devtools.serviceWorkers.testing.enabled", false);
pref("devtools.netmonitor.enabled", true);
pref("devtools.netmonitor.features.requestBlocking", true);
pref("devtools.application.enabled", true);
pref("devtools.application.selectedSidebar", "service-workers");
pref("devtools.application.sessionHistory.enabled", false);
pref("devtools.anti-tracking.enabled", false);
pref("devtools.custom-formatters.enabled", false);
pref("devtools.storage.enabled", true);
pref("devtools.styleeditor.enabled", true);
pref("devtools.styleeditor.autocompletion-enabled", true);
pref("devtools.styleeditor.showAtRulesSidebar", true);
pref("devtools.styleeditor.atRulesSidebarWidth", 238);
pref("devtools.styleeditor.navSidebarWidth", 245);
pref("devtools.styleeditor.transitions", true);
pref("devtools.screenshot.clipboard.enabled", false);
pref("devtools.screenshot.audio.enabled", true);
pref("devtools.dom.enabled", true);
pref("devtools.accessibility.enabled", true);

// Network Monitor.
pref("devtools.netmonitor.panes-network-details-width", 550);
pref("devtools.netmonitor.panes-network-details-height", 450);
pref("devtools.netmonitor.panes-search-width", 550);
pref("devtools.netmonitor.panes-search-height", 450);
pref("devtools.netmonitor.filters", "[\"all\"]");
pref("devtools.netmonitor.requestfilter", "");
pref("devtools.netmonitor.visibleColumns", "[\"override\",\"status\",\"method\",\"domain\",\"file\",\"initiator\",\"type\",\"transferred\",\"contentSize\",\"waterfall\"]");
pref("devtools.netmonitor.columnsData", '[{"name":"override","minWidth":20,"width":2},{"name":"status","minWidth":30,"width":5},{"name":"method","minWidth":30,"width":5},{"name":"domain","minWidth":30,"width":10},{"name":"file","minWidth":30,"width":25},{"name":"url","minWidth":30,"width":25},{"name":"initiator","minWidth":30,"width":10},{"name":"type","minWidth":30,"width":5},{"name":"transferred","minWidth":30,"width":10},{"name":"contentSize","minWidth":30,"width":5},{"name":"waterfall","minWidth":150,"width":15}]');
pref("devtools.netmonitor.msg.payload-preview-height", 128);
pref("devtools.netmonitor.msg.visibleColumns", '["data","time"]');
pref("devtools.netmonitor.msg.displayed-messages.limit", 500);
pref("devtools.netmonitor.ui.default-raw-response", false);
pref("devtools.netmonitor.saveRequestAndResponseBodies", true);
pref("devtools.netmonitor.har.defaultLogDir", "");
pref("devtools.netmonitor.har.defaultFileName", "%hostname_Archive [%date]");
pref("devtools.netmonitor.har.jsonp", false);
pref("devtools.netmonitor.har.jsonpCallback", "");
pref("devtools.netmonitor.har.includeResponseBodies", true);
pref("devtools.netmonitor.har.compress", false);
pref("devtools.netmonitor.har.forceExport", false);
pref("devtools.netmonitor.har.pageLoadedTimeout", 1500);
pref("devtools.netmonitor.har.enableAutoExportToFile", false);
pref("devtools.netmonitor.har.multiple-pages", false);
pref("devtools.netmonitor.audits.slow", 500);
pref("devtools.netmonitor.features.newEditAndResend", true);
pref("devtools.netmonitor.customRequest", '{}');
pref("devtools.netmonitor.features.webtransport", false);
pref("devtools.netmonitor.persistlog", false);

// Web Console and shared source editor.
pref("devtools.webconsole.filter.error", true);
pref("devtools.webconsole.filter.warn", true);
pref("devtools.webconsole.filter.info", true);
pref("devtools.webconsole.filter.log", true);
pref("devtools.webconsole.filter.debug", true);
pref("devtools.webconsole.filter.css", false);
pref("devtools.webconsole.filter.net", false);
pref("devtools.webconsole.filter.netxhr", false);
pref("devtools.webconsole.input.autocomplete", true);
pref("devtools.webconsole.input.eagerEvaluation", true);
pref("devtools.webconsole.inputHistoryCount", 300);
pref("devtools.webconsole.persistlog", false);
pref("devtools.webconsole.timestampMessages", false);
pref("devtools.webconsole.sidebarToggle", false);
pref("devtools.webconsole.input.editor", false);
pref("devtools.webconsole.input.editorWidth", 0);
pref("devtools.webconsole.input.editorOnboarding", true);
pref("devtools.webconsole.groupSimilarMessages", true);
pref("devtools.webconsole.codemirrorNext", false);
// Keep Gecko's self-XSS paste warning enabled until the user completes its
// built-in acknowledgement flow.
pref("devtools.selfxss.count", 0);
pref("devtools.source-map.client-service.enabled", true);
pref("devtools.hud.loglimit", 10000);
pref("devtools.editor.tabsize", 2);
pref("devtools.editor.expandtab", true);
pref("devtools.editor.keymap", "default");
pref("devtools.editor.autoclosebrackets", true);
pref("devtools.editor.detectindentation", true);
pref("devtools.editor.enableCodeFolding", true);
pref("devtools.editor.autocomplete", true);

// Responsive Design Mode.
pref("devtools.responsive.viewport.angle", 0);
pref("devtools.responsive.viewport.width", 320);
pref("devtools.responsive.viewport.height", 480);
pref("devtools.responsive.viewport.pixelRatio", 0);
pref("devtools.responsive.leftAlignViewport.enabled", false);
pref("devtools.responsive.reloadConditions.touchSimulation", false);
pref("devtools.responsive.reloadConditions.userAgent", false);
pref("devtools.responsive.reloadNotification.enabled", true);
pref("devtools.responsive.touchSimulation.enabled", false);
pref("devtools.responsive.userAgent", "");
pref("devtools.responsive.showUserAgentInput", true);
pref("devtools.responsive.dynamicToolbar.enabled", false);
pref("devtools.responsive.dynamicToolbar.onTop", false);

// Debugger preferences stored in Firefox's product profile rather than the
// separately packaged debugger.js schema.
pref("devtools.debugger.features.map-await-expression", true);
pref("devtools.debugger.features.async-captured-stacks", true);
pref("devtools.debugger.features.async-live-stacks", false);
pref("devtools.debugger.features.stylesheets-in-debugger", false);
pref("devtools.debugger.show-content-scripts", false);
pref("devtools.debugger.hide-ignored-sources", false);
pref("devtools.popup.disable_autohide", false);
pref("devtools.high-contrast-mode-support", false);
