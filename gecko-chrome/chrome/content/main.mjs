/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import {
  clearMaterialRipples,
  createIcon,
  createIconButton,
  createMenuController,
  installMaterialRipple,
  setIcon,
} from "chrome://navis/content/design-system.mjs";
import {
  localizeDocument,
  localizer,
  t,
} from "chrome://navis/content/localization.mjs";
import { OmniboxEditState } from "chrome://navis/content/omnibox-edit-state.mjs";
import { OmniboxSuggestions } from "chrome://navis/content/omnibox-suggestions.mjs";
import { setOmniboxValidity } from "chrome://navis/content/omnibox-validity.mjs";
import { mountProfileMenu } from "chrome://navis/content/profile-ui.mjs";
import {
  DESKTOP_EMBEDDER_API_VERSION,
  EngineRuntime,
  EngineView,
} from "chrome://navis/content/platform-api.mjs";

if (DESKTOP_EMBEDDER_API_VERSION !== 2) {
  throw new Error(
    `Navis requires Desktop Embedder source API 2, found ${DESKTOP_EMBEDDER_API_VERSION}`,
  );
}

const DEFAULT_URI = "navis://newtab/";
const TAB_HOVER_DELAY_MS = 400;
const TAB_MEMORY_CACHE_MS = 5000;
const hasVisibleLoading = (state) => state.loadingActivity === "visible";

const CONTEXT_MENU_PRESENTATION = Object.freeze({
  back: Object.freeze({ label: t("menu.back"), shortcut: "Alt+Left" }),
  forward: Object.freeze({ label: t("menu.forward"), shortcut: "Alt+Right" }),
  reload: Object.freeze({ label: t("menu.reload"), shortcut: "Ctrl+R" }),
  "open-link-new-tab": Object.freeze({ label: t("menu.openLinkNewTab") }),
  "copy-link-address": Object.freeze({ label: t("menu.copyLinkAddress") }),
  "open-image-new-tab": Object.freeze({ label: t("menu.openImageNewTab") }),
  "copy-image-address": Object.freeze({ label: t("menu.copyImageAddress") }),
  "play-media": Object.freeze({ label: t("menu.play") }),
  "pause-media": Object.freeze({ label: t("menu.pause") }),
  "mute-media": Object.freeze({ label: t("menu.mute") }),
  "unmute-media": Object.freeze({ label: t("menu.unmute") }),
  "show-media-controls": Object.freeze({ label: t("menu.showControls") }),
  "hide-media-controls": Object.freeze({ label: t("menu.hideControls") }),
  "open-media-new-tab": Object.freeze({ label: t("menu.openMediaNewTab") }),
  "copy-media-address": Object.freeze({ label: t("menu.copyMediaAddress") }),
  undo: Object.freeze({ label: t("menu.undo"), shortcut: "Ctrl+Z" }),
  redo: Object.freeze({ label: t("menu.redo"), shortcut: "Ctrl+Shift+Z" }),
  cut: Object.freeze({ label: t("menu.cut"), shortcut: "Ctrl+X" }),
  copy: Object.freeze({ label: t("menu.copy"), shortcut: "Ctrl+C" }),
  paste: Object.freeze({ label: t("menu.paste"), shortcut: "Ctrl+V" }),
  "select-all": Object.freeze({
    label: t("menu.selectAll"),
    shortcut: "Ctrl+A",
  }),
  "inspect-element": Object.freeze({
    label: t("menu.inspectElement"),
    shortcut: "Ctrl+Shift+C",
  }),
  "tab-new": Object.freeze({ label: t("chrome.newTab"), shortcut: "Ctrl+T" }),
  "tab-reload": Object.freeze({
    label: t("chrome.reload"),
    shortcut: "Ctrl+R",
  }),
  "tab-duplicate": Object.freeze({ label: t("menu.duplicateTab") }),
  "tab-close": Object.freeze({ label: t("common.close"), shortcut: "Ctrl+W" }),
  "tab-close-others": Object.freeze({ label: t("menu.closeOtherTabs") }),
  "tab-close-right": Object.freeze({ label: t("menu.closeTabsRight") }),
  "omnibox-undo": Object.freeze({ label: t("menu.undo"), shortcut: "Ctrl+Z" }),
  "omnibox-redo": Object.freeze({
    label: t("menu.redo"),
    shortcut: "Ctrl+Shift+Z",
  }),
  "omnibox-cut": Object.freeze({ label: t("menu.cut"), shortcut: "Ctrl+X" }),
  "omnibox-copy": Object.freeze({ label: t("menu.copy"), shortcut: "Ctrl+C" }),
  "omnibox-paste": Object.freeze({
    label: t("menu.paste"),
    shortcut: "Ctrl+V",
  }),
  "omnibox-paste-and-go": Object.freeze({ label: t("menu.pasteAndGo") }),
  "omnibox-paste-and-search": Object.freeze({
    label: t("menu.pasteAndSearch"),
  }),
  "omnibox-select-all": Object.freeze({
    label: t("menu.selectAll"),
    shortcut: "Ctrl+A",
  }),
  "product-copy": Object.freeze({ label: t("menu.copy"), shortcut: "Ctrl+C" }),
  "product-select-all": Object.freeze({
    label: t("menu.selectAll"),
    shortcut: "Ctrl+A",
  }),
  "bookmark-open": Object.freeze({ label: t("common.open") }),
  "bookmark-open-new-tab": Object.freeze({ label: t("menu.openLinkNewTab") }),
  "bookmark-copy-link": Object.freeze({ label: t("menu.copyLinkAddress") }),
  "bookmark-edit": Object.freeze({ label: t("common.edit") }),
  "bookmark-delete": Object.freeze({ label: t("common.delete") }),
  "bookmark-add-page": Object.freeze({ label: t("menu.addBookmarkPage") }),
  "bookmark-add-folder": Object.freeze({ label: t("menu.addBookmarkFolder") }),
  "bookmark-manager": Object.freeze({ label: t("menu.openBookmarkManager") }),
});

async function initialize() {
  localizeDocument(document);
  const sessionStrip = document.getElementById("session-strip");
  const sessionTabs = document.getElementById("session-tabs");
  const newSession = document.getElementById("new-session");
  const tabHoverCard = document.getElementById("tab-hover-card");
  const tabHoverTitle = document.getElementById("tab-hover-title");
  const tabHoverDomain = document.getElementById("tab-hover-domain");
  const tabHoverMemory = document.getElementById("tab-hover-memory");
  const windowMinimize = document.getElementById("window-minimize");
  const windowMaximize = document.getElementById("window-maximize");
  const windowClose = document.getElementById("window-close");
  const privateIndicator = document.getElementById("private-indicator");
  const viewHost = document.getElementById("session-host");
  const address = document.getElementById("url");
  const omniboxSuggestions = document.getElementById("omnibox-suggestions");
  const back = document.getElementById("back");
  const forward = document.getElementById("forward");
  const reload = document.getElementById("reload");
  const security = document.getElementById("security");
  const securityLabel = document.getElementById("security-label");
  const siteInfoPanel = document.getElementById("site-info-panel");
  const siteInfoIcon = document.getElementById("site-info-icon");
  const siteInfoTitle = document.getElementById("site-info-title");
  const siteInfoSubtitle = document.getElementById("site-info-subtitle");
  const siteInfoSummary = document.getElementById("site-info-summary");
  const siteInfoStatus = document.getElementById("site-info-status");
  const siteInfoDescription = document.getElementById("site-info-description");
  const siteDataSection = document.getElementById("site-data-section");
  const closeSiteInfo = document.getElementById("close-site-info");
  const certificateDetails = document.getElementById("certificate-details");
  const certificatePanel = document.getElementById("certificate-panel");
  const certificateBack = document.getElementById("certificate-back");
  const certificateFields = document.getElementById("certificate-fields");
  const certificateChain = document.getElementById("certificate-chain");
  const loadingState = document.getElementById("loading-state");
  const fullscreenHint = document.getElementById("fullscreen-hint");
  const fullscreenOrigin = document.getElementById("fullscreen-origin");
  const fullscreenInstruction = document.getElementById(
    "fullscreen-instruction",
  );
  const sessionCount = document.getElementById("session-count");
  const crashPanel = document.getElementById("crash-panel");
  const restoreSession = document.getElementById("restore-session");
  const permissionPanel = document.getElementById("permission-panel");
  const permissionTitle = document.getElementById("permission-title");
  const permissionOrigin = document.getElementById("permission-origin");
  const permissionMessage = document.getElementById("permission-message");
  const permissionDetail = document.getElementById("permission-detail");
  const blockPermission = document.getElementById("block-permission");
  const allowAlways = document.getElementById("allow-always");
  const allowSession = document.getElementById("allow-session");
  const promptPanel = document.getElementById("prompt-panel");
  const promptOrigin = document.getElementById("prompt-origin");
  const promptTitle = document.getElementById("prompt-title");
  const promptMessage = document.getElementById("prompt-message");
  const promptTextLabel = document.getElementById("prompt-text-label");
  const promptText = document.getElementById("prompt-text");
  const promptUsernameLabel = document.getElementById("prompt-username-label");
  const promptUsername = document.getElementById("prompt-username");
  const promptPasswordLabel = document.getElementById("prompt-password-label");
  const promptPasswordLabelText = document.getElementById(
    "prompt-password-label-text",
  );
  const promptPassword = document.getElementById("prompt-password");
  const promptChoiceLabel = document.getElementById("prompt-choice-label");
  const promptChoice = document.getElementById("prompt-choice");
  const promptCheckboxLabel = document.getElementById("prompt-checkbox-label");
  const promptCheckbox = document.getElementById("prompt-checkbox");
  const promptCheckboxText = document.getElementById("prompt-checkbox-text");
  const promptActions = document.getElementById("prompt-actions");
  const mediaIndicator = document.getElementById("media-indicator");
  const downloadsPanel = document.getElementById("downloads-panel");
  const downloadsToggle = document.getElementById("downloads-toggle");
  const downloadCount = document.getElementById("download-count");
  const downloadToolbarProgress = document.getElementById(
    "download-toolbar-progress",
  );
  const downloadList = document.getElementById("download-list");
  const downloadsEmpty = document.getElementById("downloads-empty");
  const extensionActionButtons = document.getElementById(
    "extension-action-buttons",
  );
  const extensionPageActionButtons = document.getElementById(
    "extension-page-actions",
  );
  const extensionsMenuToggle = document.getElementById(
    "extensions-menu-toggle",
  );
  const extensionsMenu = document.getElementById("extensions-menu");
  const extensionActionList = document.getElementById("extension-action-list");
  const extensionActionsEmpty = document.getElementById(
    "extension-actions-empty",
  );
  const manageExtensions = document.getElementById("manage-extensions");
  const bookmarkCurrent = document.getElementById("bookmark-current");
  const passwordToggle = document.getElementById("password-toggle");
  const libraryToggle = document.getElementById("library-toggle");
  const appMenuToggle = document.getElementById("app-menu-toggle");
  const appMenu = document.getElementById("app-menu");

  const positionAnchoredSurface = (
    surface,
    anchor,
    { align = "end", anchorRect = null } = {},
  ) => {
    const source = anchorRect ?? anchor?.getBoundingClientRect();
    if (!source) {
      return;
    }
    const margin = 8;
    const gap = 4;
    surface.style.removeProperty("max-height");
    const surfaceBounds = surface.getBoundingClientRect();
    const viewportWidth = document.documentElement.clientWidth;
    const viewportHeight = document.documentElement.clientHeight;
    const availableBelow = Math.max(
      0,
      viewportHeight - source.bottom - gap - margin,
    );
    const availableAbove = Math.max(0, source.top - gap - margin);
    const placeAbove =
      surfaceBounds.height > availableBelow && availableAbove > availableBelow;
    const availableHeight = placeAbove ? availableAbove : availableBelow;
    const renderedHeight = Math.min(surfaceBounds.height, availableHeight);
    const surfaceTop = placeAbove
      ? Math.max(margin, source.top - gap - renderedHeight)
      : source.bottom + gap;
    const preferredLeft =
      align === "start" ? source.left : source.right - surfaceBounds.width;
    const maximumLeft = Math.max(
      margin,
      viewportWidth - surfaceBounds.width - margin,
    );
    const left = Math.min(Math.max(margin, preferredLeft), maximumLeft);
    surface.style.top = `${Math.round(surfaceTop)}px`;
    surface.style.left = `${Math.round(left)}px`;
    surface.style.right = "auto";
    surface.style.maxHeight = `${Math.max(80, Math.floor(availableHeight))}px`;
  };
  const contextMenuController = createMenuController(
    document.getElementById("context-menu"),
  );
  const newPrivateWindow = document.getElementById("new-private-window");
  const menuHistory = document.getElementById("menu-history");
  const menuBookmarks = document.getElementById("menu-bookmarks");
  const menuPasswords = document.getElementById("menu-passwords");
  const menuDownloads = document.getElementById("menu-downloads");
  const menuProcesses = document.getElementById("menu-processes");
  const menuProfiles = document.getElementById("menu-profiles");
  const menuSupport = document.getElementById("menu-support");
  const profileToggle = document.getElementById("profile-toggle");
  const profileMenu = document.getElementById("profile-menu");
  const menuDeveloperTools = document.getElementById("menu-developer-tools");
  const menuSettings = document.getElementById("menu-settings");
  const clearSiteData = document.getElementById("clear-site-data");
  const siteDataSummary = document.getElementById("site-data-summary");
  const bookmarkBar = document.getElementById("bookmark-bar");
  const bookmarkBarItems = document.getElementById("bookmark-bar-items");
  const bookmarkBarEmpty = document.getElementById("bookmark-bar-empty");
  const bookmarkBarOverflow = document.getElementById("bookmark-bar-overflow");
  const bookmarkBarPopup = document.getElementById("bookmark-bar-popup");
  const libraryPanel = document.getElementById("library-panel");
  const libraryTitle = document.getElementById("library-title");
  const openLibraryPage = document.getElementById("open-library-page");
  const closeLibrary = document.getElementById("close-library");
  const historyView = document.getElementById("history-view");
  const bookmarksView = document.getElementById("bookmarks-view");
  const passwordsView = document.getElementById("passwords-view");
  const historySearch = document.getElementById("history-search");
  const historyList = document.getElementById("history-list");
  const historyEmpty = document.getElementById("history-empty");
  const clearHistory = document.getElementById("clear-history");
  const bookmarkUp = document.getElementById("bookmark-up");
  const addCurrentBookmark = document.getElementById("add-current-bookmark");
  const addBookmarkFolder = document.getElementById("add-bookmark-folder");
  const bookmarkEditor = document.getElementById("bookmark-editor");
  const bookmarkEditorTitle = document.getElementById("bookmark-editor-title");
  const bookmarkEditId = document.getElementById("bookmark-edit-id");
  const bookmarkEditType = document.getElementById("bookmark-edit-type");
  const bookmarkEditTitle = document.getElementById("bookmark-edit-title");
  const bookmarkEditUrlLabel = document.getElementById(
    "bookmark-edit-url-label",
  );
  const bookmarkEditUrl = document.getElementById("bookmark-edit-url");
  const cancelBookmarkEdit = document.getElementById("cancel-bookmark-edit");
  const bookmarksList = document.getElementById("bookmarks-list");
  const bookmarksEmpty = document.getElementById("bookmarks-empty");
  const passwordsList = document.getElementById("passwords-list");
  const passwordsEmpty = document.getElementById("passwords-empty");
  const passwordsPrivateNote = document.getElementById(
    "passwords-private-note",
  );
  const clearPasswords = document.getElementById("clear-passwords");
  const developerToolsShortcutBindings = Object.freeze([
    Object.freeze({
      element: document.getElementById("navis-toggle-devtools-key"),
      toolId: null,
    }),
    Object.freeze({
      element: document.getElementById("navis-toggle-devtools-shortcut-key"),
      toolId: null,
    }),
    Object.freeze({
      element: document.getElementById("navis-inspector-devtools-key"),
      toolId: "inspector",
    }),
    Object.freeze({
      element: document.getElementById("navis-webconsole-devtools-key"),
      toolId: "webconsole",
    }),
    Object.freeze({
      element: document.getElementById("navis-debugger-devtools-key"),
      toolId: "jsdebugger",
    }),
    Object.freeze({
      element: document.getElementById("navis-network-devtools-key"),
      toolId: "netmonitor",
    }),
  ]);
  const viewPanels = new WeakMap();
  const createView = () => {
    const panel = document.createXULElement("hbox");
    panel.className = "desktop-engine-session-panel browserSidebarContainer";
    panel.hidden = true;

    const container = document.createXULElement("vbox");
    container.className = "desktop-engine-browser-container browserContainer";
    const stack = document.createXULElement("stack");
    stack.className = "desktop-engine-browser-stack browserStack";
    container.append(stack);
    panel.append(container);
    viewHost.append(panel);

    const view = new EngineView({ document, host: stack });
    viewPanels.set(view, panel);
    return view;
  };
  const runtime = new EngineRuntime();
  runtime.setCredentialReauthenticationPresentation({
    reason: t("passwords.reauthenticationReason"),
    caption: t("app.name"),
  });
  const privateMode = runtime.getWindowState(window).private;
  document.documentElement.toggleAttribute("data-private", privateMode);
  if (privateMode) {
    document.documentElement.dataset.theme = "private";
  }
  const records = new Map();
  const permissionResolvers = new Map();
  const promptResolvers = new Map();
  const webAuthnResolvers = new Map();
  let activeRecord = null;
  const addressEditState = new OmniboxEditState();
  let transientStatusTimer = null;
  let restoringSessions = true;
  let sessionPersistenceTimer = null;
  let historyRenderIdentity = 0;
  let bookmarkRenderIdentity = 0;
  let credentialRenderIdentity = 0;
  let bookmarkFolderStack = [];
  let revealedCredential = null;
  let clearSiteDataTimer = null;
  let clearPasswordsTimer = null;
  let fullscreenHintTimer = null;
  let siteInfoNavigationId = null;
  let contextMenuOwner = null;
  let bookmarkBarRenderIdentity = 0;
  let bookmarkBarEntries = [];
  let bookmarkOverflowEntries = [];
  let libraryView = "history";
  let closeCompetingTransientSurfaces = () => {};
  let transientSurfaceForPath = () => null;
  const extensionActionAnchorIds = new Map();
  const extensionPageActionAnchorIds = new Map();
  let nextExtensionActionAnchorId = 1;
  let nextExtensionPageActionAnchorId = 1;
  let extensionActionRenderFrame = 0;
  let extensionOmniboxState = Object.freeze({
    active: false,
    suggestions: Object.freeze([]),
  });
  let extensionOmniboxSelection = 0;
  let ordinarySuggestionRows = [];
  let ordinarySuggestionQuery = "";
  let addressComposing = false;
  let ordinarySuggestions = null;
  let tabWheelDelta = 0;
  let tabWheelResetTimer = null;
  let lastTabWheelSwitch = 0;
  let draggedTabRecord = null;
  let tabDropTarget = null;
  let tabDropBefore = false;
  let tabHoverRecord = null;
  let tabHoverTimer = null;
  let tabHoverRequestId = 0;
  let selectAddressOnActivationClick = false;

  setIcon(newSession, "plus");
  setIcon(windowMinimize, "minimize");
  setIcon(windowMaximize, "maximize");
  setIcon(windowClose, "close");
  setIcon(bookmarkBarOverflow, "overflow");
  setIcon(back, "back");
  setIcon(forward, "forward");
  setIcon(reload, "reload");
  setIcon(security, "info");
  setIcon(siteInfoIcon, "info");
  setIcon(closeSiteInfo, "close");
  setIcon(certificateDetails, "certificate");
  setIcon(certificateBack, "back");
  setIcon(extensionsMenuToggle, "extension");
  setIcon(document.getElementById("close-extensions-menu"), "close");
  setIcon(downloadsToggle, "downloads");
  setIcon(document.getElementById("dismiss-permission"), "close");
  setIcon(document.getElementById("dismiss-prompt"), "close");
  setIcon(document.getElementById("close-downloads"), "close");
  setIcon(document.getElementById("crash-icon"), "warning");
  setIcon(bookmarkCurrent, "star");
  setIcon(passwordToggle, "password");
  setIcon(libraryToggle, "history");
  setIcon(appMenuToggle, "menu");
  setIcon(profileToggle, "profile");
  for (const [button, icon] of [
    [newPrivateWindow, "private"],
    [menuHistory, "history"],
    [menuBookmarks, "star"],
    [menuPasswords, "password"],
    [menuDownloads, "downloads"],
    [menuProcesses, "processes"],
    [menuProfiles, "profile"],
    [menuSupport, "info"],
    [menuDeveloperTools, "developerTools"],
    [menuSettings, "siteControls"],
  ]) {
    setIcon(button, icon);
  }
  for (const [button, labelId] of [
    [bookmarkCurrent, "chrome.bookmarkPage"],
    [extensionsMenuToggle, "extensions.toolbarTitle"],
  ]) {
    button.className = "menu-item";
    button.setAttribute("role", "menuitem");
    const label = document.createElement("span");
    label.className = "menu-item-label";
    label.textContent = t(labelId);
    button.append(label);
    appMenu.insertBefore(button, menuHistory);
  }
  extensionActionList.before(extensionPageActionButtons);
  setIcon(closeLibrary, "close");
  setIcon(openLibraryPage, "openPage");
  installMaterialRipple(document);
  privateIndicator.hidden = !privateMode;

  const presentContextMenuItems = (items) =>
    items.flatMap((item) => {
      if (item.id === "search-selection") {
        return [{...item, label: t("menu.searchSelection", {
          provider: item.providerName, text: item.excerpt}), enabled: item.enabled !== false}];
      }
      if (item.group?.startsWith("extension:")) {
        return [
          {
            ...item,
            label: item.label,
            enabled: item.enabled !== false,
            children: presentContextMenuItems(item.children || []),
          },
        ];
      }
      if (item.group === "spelling" && item.suggestion) {
        return [
          { ...item, label: item.suggestion, enabled: item.enabled !== false },
        ];
      }
      const presentation = CONTEXT_MENU_PRESENTATION[item.id];
      return presentation
        ? [{ ...item, ...presentation, enabled: item.enabled !== false }]
        : [];
    });

  const openGeckoContextMenu = ({
    ownerId,
    x,
    y,
    triggerEvent = null,
    label,
    items,
    onSelect,
    onDismiss,
    restoreFocus,
  }) => {
    const relatedSurface = triggerEvent
      ? transientSurfaceForPath(triggerEvent.composedPath())
      : null;
    closeCompetingTransientSurfaces(
      relatedSurface ?? "context-menu",
      "competing-surface",
    );
    const owner = { id: ownerId, relatedSurface };
    contextMenuController.open({
      x,
      y,
      triggerEvent,
      label,
      items: presentContextMenuItems(items),
      onSelect: (command) => {
        if (contextMenuOwner === owner) {
          contextMenuOwner = null;
        }
        onSelect(command);
      },
      onDismiss: (reason) => {
        if (contextMenuOwner === owner) {
          contextMenuOwner = null;
        }
        onDismiss?.(reason);
      },
      restoreFocus,
    });
    contextMenuOwner = owner;
  };

  const dismissGeckoContextMenu = (ownerId) => {
    if (contextMenuOwner?.id !== ownerId) {
      return false;
    }
    contextMenuOwner = null;
    return contextMenuController.close("binding-dismissed", {
      notify: false,
      restoreFocus: false,
    });
  };

  const dismissGeckoContextMenuAfterContentInteraction = () => {
    const owner = contextMenuOwner;
    if (!owner) {
      return;
    }
    // A real content click must roll up an open menu.  Defer only the menu
    // close by one task because Windows can project the pointerdown that
    // selected a native XUL menu item into the underlying content before the
    // command event has finished.  Command selection clears the owner first;
    // a genuine outside click leaves the same owner in place and is closed.
    window.setTimeout(() => {
      if (contextMenuOwner === owner) {
        contextMenuController.close("content-interaction", {
          restoreFocus: false,
        });
      }
    }, 0);
  };

  runtime.setNavigationDelegate({
    onNewSession: (request) => ({
      allow: true,
      activate: request.disposition !== "background",
      view: createView(),
    }),
    onSessionCreated: ({ session, activate }) => {
      adoptSession(session, { activate });
    },
    onPopupBlocked: (request) => {
      showTransientStatus(t("chrome.popupBlocked", { reason: request.reason }));
    },
  });

  const orderedSessionRecords = () => [...records.values()];

  const synchronizeSessionOrder = () => {
    const recordsByTab = new Map(
      orderedSessionRecords().map((record) => [record.tab, record]),
    );
    const ordered = [...sessionTabs.children]
      .map((tab) => recordsByTab.get(tab))
      .filter(Boolean);
    if (ordered.length !== records.size) {
      return false;
    }
    records.clear();
    for (const record of ordered) {
      records.set(record.session.id, record);
    }
    return true;
  };

  const saveCurrentSessionState = () => {
    const sessions = orderedSessionRecords()
      .map((record) => record.session)
      .filter((session) => !session.state.closed);
    if (privateMode || !sessions.length) {
      return Promise.resolve(false);
    }
    const selectedSession = sessions.includes(activeRecord?.session)
      ? activeRecord.session
      : sessions.at(-1);
    return runtime.saveSessionState({ sessions, selectedSession });
  };

  const persistSessionState = ({ immediate = false } = {}) => {
    if (privateMode || restoringSessions || !records.size || !activeRecord) {
      return;
    }
    clearTimeout(sessionPersistenceTimer);
    const persist = () => {
      sessionPersistenceTimer = null;
      saveCurrentSessionState().catch((error) => {
        console.error("Navis could not persist Session state", error);
      });
    };
    if (immediate) {
      persist();
    } else {
      sessionPersistenceTimer = setTimeout(persist, 100);
    }
  };

  const updateSessionPresentation = (session) => {
    const record = records.get(session.id);
    if (!record || session.state.closed) {
      return;
    }
    const navigationChanged =
      record.navigationId !== session.state.navigationId;
    record.navigationId = session.state.navigationId;
    if (navigationChanged) {
      record.memoryUsage = null;
      record.memoryMeasuredAt = 0;
    }
    if (navigationChanged && record === activeRecord) {
      if (!addressEditState.editing) {
        addressEditState.reset();
      }
      closeCompetingTransientSurfaces(null, "navigation");
    }
    renderSessionTab(record);
    if (
      navigationChanged &&
      tabHoverRecord === record &&
      !tabHoverCard.hidden
    ) {
      showTabHoverCard(record).catch(() => {
        if (tabHoverRecord === record) {
          tabHoverMemory.textContent = t("tabs.memoryUnavailable");
        }
      });
    }
    if (record === activeRecord) {
      renderActiveSession();
    }
    persistSessionState({ immediate: navigationChanged });
  };

  runtime.setSessionDelegateFactory((session) => ({
    content: {
      onStateChanged: () => updateSessionPresentation(session),
      onInteraction: () => {
        if (records.get(session.id) === activeRecord) {
          closeCompetingTransientSurfaces(
            "context-menu",
            "content-interaction",
          );
          dismissGeckoContextMenuAfterContentInteraction();
        }
      },
    },
    contextMenu: {
      onShow: (request) => {
        const record = records.get(session.id);
        if (!record || record !== activeRecord) {
          return false;
        }
        openGeckoContextMenu({
          ownerId: request.id,
          x: request.position.x,
          y: request.position.y,
          label: t("menu.pageContext"),
          items: request.items,
          onSelect: (command) => {
            session.executeContextMenuCommand(request.id, command);
            if (activeRecord === record) {
              session.view?.focus();
            }
          },
          onDismiss: () => session.dismissContextMenu(request.id),
          restoreFocus: () => session.view?.focus(),
        });
        return true;
      },
      onDismissed: (request) => {
        dismissGeckoContextMenu(request.id);
      },
    },
    permission: {
      onRequest: (request) =>
        new Promise((resolve) => {
          permissionResolvers.set(request.id, {
            sessionId: session.id,
            resolve,
          });
          updateSessionPresentation(session);
        }),
      onCanceled: (request) => {
        const pending = permissionResolvers.get(request.id);
        if (pending?.sessionId === session.id) {
          permissionResolvers.delete(request.id);
          pending.resolve("dismiss");
        }
        updateSessionPresentation(session);
      },
    },
    prompt: {
      onPrompt: (request) =>
        new Promise((resolve) => {
          promptResolvers.set(request.id, {
            sessionId: session.id,
            request,
            resolve,
          });
          updateSessionPresentation(session);
        }),
      onCanceled: (request) => {
        const pending = promptResolvers.get(request.id);
        if (pending?.sessionId === session.id) {
          promptResolvers.delete(request.id);
          pending.resolve({ action: "dismiss" });
        }
        updateSessionPresentation(session);
      },
    },
    webAuthn: {
      onRequest: (request) =>
        new Promise((resolve) => {
          webAuthnResolvers.set(request.id, {
            sessionId: session.id,
            request,
            resolve,
          });
          updateSessionPresentation(session);
        }),
      onCanceled: (request) => {
        const pending = webAuthnResolvers.get(request.id);
        if (pending?.sessionId === session.id) {
          webAuthnResolvers.delete(request.id);
          pending.resolve({ action: "cancel" });
        }
        updateSessionPresentation(session);
      },
    },
    media: runtime.capabilities.mediaCapture
      ? {
          onStateChanged: (media) => {
            const record = records.get(session.id);
            if (record) {
              record.media = media;
            }
            updateSessionPresentation(session);
          },
        }
      : null,
    crash: {
      onCrash: (detail) => {
        updateSessionPresentation(session);
        console.error("Navis content process crashed", detail);
      },
    },
  }));

  await runtime.ready;

  const showTransientStatus = (message) => {
    loadingState.textContent = message;
    loadingState.setAttribute("aria-label", message);
    loadingState.dataset.visible = "true";
    clearTimeout(transientStatusTimer);
    transientStatusTimer = setTimeout(() => {
      transientStatusTimer = null;
      renderActiveSession();
    }, 2500);
  };

  const extensionActionAnchorId = (extensionId) => {
    if (!extensionActionAnchorIds.has(extensionId)) {
      extensionActionAnchorIds.set(
        extensionId,
        `extension-action-${nextExtensionActionAnchorId++}`,
      );
    }
    return extensionActionAnchorIds.get(extensionId);
  };

  const extensionPageActionAnchorId = (extensionId) => {
    if (!extensionPageActionAnchorIds.has(extensionId)) {
      extensionPageActionAnchorIds.set(
        extensionId,
        `extension-page-action-${nextExtensionPageActionAnchorId++}`,
      );
    }
    return extensionPageActionAnchorIds.get(extensionId);
  };

  const extensionIcon = (action) => {
    if (!action.icon) {
      return createIcon(document, "extension");
    }
    const image = document.createElement("img");
    image.className = "extension-action-icon";
    image.src = action.icon;
    image.alt = "";
    image.draggable = false;
    return image;
  };

  const badgeColor = (color) =>
    `rgba(${color[0]}, ${color[1]}, ${color[2]}, ${color[3] / 255})`;

  const extensionActionByButton = new WeakMap();
  const extensionPageActionByButton = new WeakMap();

  const updateExtensionButtonIcon = (button, action, before = null) => {
    const current = button.querySelector(
      ":scope > .extension-action-icon, :scope > .navis-icon",
    );
    const currentMatches = action.icon
      ? current?.localName === "img" &&
        current.getAttribute("src") === action.icon
      : current?.classList.contains("navis-icon") &&
        current.getAttribute("data-icon") === "extension";
    if (currentMatches) {
      return;
    }
    const icon = extensionIcon(action);
    button.insertBefore(icon, before);
    current?.remove();
  };

  const invokeExtensionAction = async (action, anchorId) => {
    const record = activeRecord;
    if (!record || !action.enabled) {
      return;
    }
    try {
      await runtime.triggerExtensionAction(
        record.session,
        action.extensionId,
        anchorId,
      );
    } catch (error) {
      showTransientStatus(t("extensions.actionFailed"));
      console.error("Navis extension action failed", error);
    }
  };

  const invokeExtensionPageAction = async (action, anchorId) => {
    const record = activeRecord;
    if (!record || !action.enabled) {
      return;
    }
    try {
      await runtime.triggerExtensionPageAction(
        record.session,
        action.extensionId,
        anchorId,
      );
    } catch (error) {
      showTransientStatus(t("extensions.actionFailed"));
      console.error("Navis extension page action failed", error);
    }
  };

  const createExtensionActionButton = () => {
    const button = document.createElement("button");
    button.className = "ui-icon-button extension-action-button";
    button.type = "button";
    const badge = document.createElement("span");
    badge.className = "extension-action-badge";
    button.append(badge);
    button.addEventListener("click", () => {
      const currentAction = extensionActionByButton.get(button);
      if (currentAction) {
        invokeExtensionAction(currentAction, button.id);
      }
    });
    return button;
  };

  function updateExtensionActionButton(button, action) {
    button.id = extensionActionAnchorId(action.extensionId);
    button.dataset.extensionId = action.extensionId;
    button.title = action.title || action.name;
    button.setAttribute("aria-label", action.title || action.name);
    button.disabled = !action.enabled;
    extensionActionByButton.set(button, action);
    const badge = button.lastElementChild;
    updateExtensionButtonIcon(button, action, badge);
    badge.textContent = action.badgeText;
    badge.style.backgroundColor = badgeColor(action.badgeBackgroundColor);
    badge.style.color = badgeColor(action.badgeTextColor);
  }

  const createExtensionPageActionButton = () => {
    const button = document.createElement("button");
    button.className =
      "ui-icon-button extension-action-button extension-page-action-button";
    button.type = "button";
    button.addEventListener("click", () => {
      const currentAction = extensionPageActionByButton.get(button);
      if (currentAction) {
        invokeExtensionPageAction(currentAction, button.id);
      }
    });
    return button;
  };

  function updateExtensionPageActionButton(button, action) {
    button.id = extensionPageActionAnchorId(action.extensionId);
    button.dataset.extensionId = action.extensionId;
    button.title = action.title || action.name;
    button.setAttribute("aria-label", action.title || action.name);
    extensionPageActionByButton.set(button, action);
    updateExtensionButtonIcon(button, action);
  }

  const reconcileExtensionActionButtons = (
    container,
    actions,
    createButton,
    updateButton,
  ) => {
    const existing = new Map(
      [...container.children].map((button) => [
        button.dataset.extensionId,
        button,
      ]),
    );
    let insertionPoint = container.firstElementChild;
    for (const action of actions) {
      const button = existing.get(action.extensionId) || createButton(action);
      existing.delete(action.extensionId);
      updateButton(button, action);
      if (button === insertionPoint) {
        insertionPoint = insertionPoint.nextElementSibling;
      } else {
        container.insertBefore(button, insertionPoint);
      }
    }
    for (const button of existing.values()) {
      button.remove();
    }
  };

  const createExtensionMenuItem = (action) => {
    const item = document.createElement("div");
    item.className = "extension-menu-item";
    item.setAttribute("role", "listitem");
    const primary = document.createElement("button");
    primary.className = "extension-menu-primary";
    primary.type = "button";
    primary.disabled = !action.enabled;
    primary.append(extensionIcon(action));
    const copy = document.createElement("span");
    const actionName = document.createElement("strong");
    actionName.textContent = action.name;
    const detail = document.createElement("small");
    detail.textContent = action.title || action.name;
    copy.append(actionName, detail);
    primary.append(copy);
    primary.addEventListener("click", () => {
      setExtensionsMenuOpen(false);
      invokeExtensionAction(action, "extensions-menu-toggle");
    });
    const pinLabel = t(
      action.toolbarPinned ? "extensions.unpinAction" : "extensions.pinAction",
      { name: action.name },
    );
    const pin = createIconButton(document, {
      className: "extension-menu-pin",
      icon: "pin",
      label: pinLabel,
    });
    pin.setAttribute("aria-pressed", String(action.toolbarPinned));
    pin.addEventListener("click", async () => {
      pin.disabled = true;
      try {
        await runtime.setExtensionActionPinned(
          action.extensionId,
          !action.toolbarPinned,
        );
      } catch (error) {
        showTransientStatus(t("extensions.actionFailed"));
        console.error("Navis extension pin command failed", error);
      }
    });
    item.append(primary, pin);
    return item;
  };

  const renderExtensionActions = () => {
    const actions = activeRecord
      ? runtime.getExtensionActions(activeRecord.session)
      : [];
    const pageActions = activeRecord
      ? runtime.getExtensionPageActions(activeRecord.session)
      : [];
    reconcileExtensionActionButtons(
      extensionPageActionButtons,
      pageActions,
      createExtensionPageActionButton,
      updateExtensionPageActionButton,
    );
    reconcileExtensionActionButtons(
      extensionActionButtons,
      actions.filter((action) => action.toolbarPinned),
      createExtensionActionButton,
      updateExtensionActionButton,
    );
    extensionActionList.replaceChildren(
      ...actions.map(createExtensionMenuItem),
    );
    extensionActionsEmpty.hidden = actions.length !== 0;
  };

  const scheduleExtensionActionsRender = () => {
    if (extensionActionRenderFrame) {
      return;
    }
    extensionActionRenderFrame = window.requestAnimationFrame(() => {
      extensionActionRenderFrame = 0;
      renderExtensionActions();
    });
  };

  const ordinaryOmniboxChoices = () => {
    if (!ordinarySuggestionQuery || addressComposing) return [];
    const provider = runtime.searchProviders.find(item => item.id === runtime.defaultSearchProvider);
    let primary;
    try {
      const resolved = runtime.resolveAddressInput(ordinarySuggestionQuery, {privateMode});
      primary = {kind: resolved.kind === "url" ? "visit" : "search",
        text: ordinarySuggestionQuery, url: resolved.url, title: ordinarySuggestionQuery};
    } catch {
      return [];
    }
    return [primary, ...ordinarySuggestionRows].map(item => ({
      ...item,
      description: item.title,
      detail: item.kind === "search" ? t("chrome.suggestionSearch", {provider: provider?.name || ""})
        : item.kind === "visit" ? t("chrome.suggestionVisit")
          : `${t({tab: "chrome.suggestionTab", bookmark: "chrome.suggestionBookmark",
            history: "chrome.suggestionHistory"}[item.kind])} · ${item.url}`,
      deletable: false,
    }));
  };

  const extensionOmniboxChoices = () => {
    if (!extensionOmniboxState.active) {
      return ordinaryOmniboxChoices();
    }
    return [
      {
        content: null,
        deletable: false,
        description:
          extensionOmniboxState.defaultDescription ||
          extensionOmniboxState.extensionName,
        detail: extensionOmniboxState.input,
      },
      ...extensionOmniboxState.suggestions.map((suggestion) => ({
        ...suggestion,
        detail: suggestion.content,
      })),
    ];
  };

  const renderExtensionOmnibox = (state) => {
    extensionOmniboxState = state;
    const choices = extensionOmniboxChoices();
    extensionOmniboxSelection = Math.max(
      0,
      Math.min(extensionOmniboxSelection, choices.length - 1),
    );
    const rows = choices.map((choice, index) => {
      const row = document.createElement("button");
      row.id = `extension-omnibox-suggestion-${index}`;
      row.className = "omnibox-suggestion";
      row.type = "button";
      row.tabIndex = -1;
      row.setAttribute("role", "option");
      row.setAttribute(
        "aria-selected",
        String(index === extensionOmniboxSelection),
      );
      const copy = document.createElement("span");
      copy.className = "omnibox-suggestion-copy";
      const description = document.createElement("strong");
      description.textContent = choice.description;
      const detail = document.createElement("small");
      detail.textContent = choice.detail;
      copy.append(description, detail);
      row.append(copy);
      row.addEventListener("mousedown", (event) => event.preventDefault());
      row.addEventListener("click", () => {
        extensionOmniboxSelection = index;
        loadAddress();
        address.blur();
        activeRecord?.session.view?.focus();
      });
      return row;
    });
    omniboxSuggestions.replaceChildren(...rows);
    omniboxSuggestions.hidden = !choices.length;
    address.setAttribute("aria-expanded", String(Boolean(choices.length)));
    if (choices.length) {
      address.setAttribute(
        "aria-activedescendant",
        `extension-omnibox-suggestion-${extensionOmniboxSelection}`,
      );
      rows[extensionOmniboxSelection]?.scrollIntoView({block: "nearest"});
    } else {
      address.removeAttribute("aria-activedescendant");
    }
  };

  const cancelExtensionOmnibox = () => {
    ordinarySuggestions?.clear();
    if (extensionOmniboxState.active && activeRecord) {
      runtime.cancelExtensionOmnibox(activeRecord.session);
    }
    renderExtensionOmnibox(
      Object.freeze({ active: false, suggestions: Object.freeze([]) }),
    );
  };

  const updateExtensionOmnibox = () => {
    if (!activeRecord) {
      cancelExtensionOmnibox();
      return;
    }
    extensionOmniboxSelection = 0;
    const state = addressComposing
      ? Object.freeze({active: false, suggestions: Object.freeze([])})
      : runtime.updateExtensionOmnibox(activeRecord.session, address.value);
    if (state.active) ordinarySuggestions?.clear();
    renderExtensionOmnibox(state);
    if (!state.active) ordinarySuggestions?.update(address.value, {composing: addressComposing});
  };

  ordinarySuggestions = new OmniboxSuggestions({
    local: (query, options) => activeRecord
      ? runtime.getLocalSuggestions(activeRecord.session, query, options) : [],
    remote: (query, options) => activeRecord
      ? runtime.getNetworkSuggestions(activeRecord.session, query, options) : [],
    changed: ({query, rows}) => {
      const previous = ordinaryOmniboxChoices()[extensionOmniboxSelection];
      ordinarySuggestionQuery = query;
      ordinarySuggestionRows = rows;
      if (query && (document.activeElement !== address || addressComposing || extensionOmniboxState.active)) return;
      const key = item => item ? `${item.kind}:${item.id || item.url || item.text}` : "";
      const index = ordinaryOmniboxChoices().findIndex(item => key(item) === key(previous));
      extensionOmniboxSelection = Math.max(0, index);
      renderExtensionOmnibox(extensionOmniboxState);
    },
  });

  runtime.setExtensionOmniboxDelegate({
    onStateChanged: (state) => {
      if (
        state.sessionId === activeRecord?.session.id &&
        addressEditState.editing
      ) {
        if (state.active) ordinarySuggestions.clear();
        renderExtensionOmnibox(state);
      }
    },
  });

  const setExtensionsMenuOpen = (shouldOpen) => {
    extensionsMenu.hidden = !shouldOpen;
    extensionsMenuToggle.setAttribute("aria-expanded", String(shouldOpen));
    if (!shouldOpen) {
      clearMaterialRipples(extensionsMenu);
      return;
    }
    closeCompetingTransientSurfaces("extensions", "competing-surface");
    renderExtensionActions();
    positionAnchoredSurface(extensionsMenu, appMenuToggle);
    extensionActionList.querySelector("button:not(:disabled)")?.focus();
  };

  runtime.setExtensionActionDelegate({
    onActionsChanged: scheduleExtensionActionsRender,
  });

  const discardRevealedCredential = () => {
    revealedCredential = null;
    // A revealed password must not remain in the chrome DOM after the
    // management surface closes. The next open rebuilds the summaries.
    passwordsList.replaceChildren();
  };

  const setAppMenuOpen = (shouldOpen) => {
    appMenu.hidden = !shouldOpen;
    appMenuToggle.setAttribute("aria-expanded", String(shouldOpen));
    if (shouldOpen) {
      closeCompetingTransientSurfaces("app-menu", "competing-surface");
      positionAnchoredSurface(appMenu, appMenuToggle);
      appMenu.querySelector('[role="menuitem"]')?.focus();
    } else {
      clearMaterialRipples(appMenu);
    }
  };

  let profileMenuGeneration = 0;
  const setProfileMenuOpen = async (open) => {
    const generation = ++profileMenuGeneration;
    profileMenu.hidden = !open;
    profileToggle.setAttribute("aria-expanded", String(open));
    if (!open) {
      profileMenu.removeAttribute("aria-busy");
      clearMaterialRipples(profileMenu);
      return;
    }
    closeCompetingTransientSurfaces("profile", "competing-surface");
    profileMenu.setAttribute("aria-busy", "true");
    profileMenu.textContent = t("common.loading");
    positionAnchoredSurface(profileMenu, profileToggle);
    try {
      await mountProfileMenu({
        container: profileMenu, locale: localizer.locale,
        current: () => runtime.getCurrentProfile(),
        update: value => runtime.updateCurrentProfile(value),
        create: value => runtime.createProfile(value),
        launch: id => runtime.launchProfile(id),
        navigate: uri => loadProductPage(uri),
        dismiss: () => setProfileMenuOpen(false),
        isCurrent: () => generation === profileMenuGeneration && !profileMenu.hidden,
        returnFocus: profileToggle,
      });
      if (generation === profileMenuGeneration && !profileMenu.hidden) {
        profileMenu.removeAttribute("aria-busy");
        positionAnchoredSurface(profileMenu, profileToggle);
        profileMenu.querySelector("button:not(:disabled)")?.focus();
      }
    } catch (error) {
      if (generation === profileMenuGeneration && !profileMenu.hidden) {
        profileMenu.removeAttribute("aria-busy");
        profileMenu.textContent = t("settings.failed");
      }
      console.error("Navis profile menu failed", error);
    }
  };

  const makeLibraryAction = (icon, label, handler) => {
    const button = createIconButton(document, { icon, label });
    button.addEventListener("click", handler);
    return button;
  };

  const renderHistory = async () => {
    const identity = ++historyRenderIdentity;
    const entries = await runtime.queryHistory({
      search: historySearch.value,
      limit: 200,
    });
    if (identity !== historyRenderIdentity) {
      return;
    }
    const fragment = document.createDocumentFragment();
    for (const entry of entries) {
      const row = document.createElement("div");
      row.className = "library-row";
      const primary = document.createElement("button");
      primary.className = "library-primary";
      primary.type = "button";
      primary.title = entry.url;
      const title = document.createElement("strong");
      title.textContent = entry.title || entry.url;
      const detail = document.createElement("small");
      detail.textContent = t("management.historyDate", {
        date: localizer.dateTime(entry.lastVisitedAt, {
          dateStyle: "medium",
          timeStyle: "short",
        }),
        url: entry.url,
      });
      primary.append(title, detail);
      primary.addEventListener("click", () => {
        activeRecord?.session.loadUri(entry.url);
        setLibraryOpen(false);
      });
      const actions = document.createElement("div");
      actions.className = "library-row-actions";
      actions.append(
        makeLibraryAction(
          "trash",
          t("history.deleteNamed", { name: title.textContent }),
          async () => {
            await runtime.deleteHistory([entry.url]);
            await renderHistory();
          },
        ),
      );
      row.append(primary, actions);
      fragment.append(row);
    }
    historyList.replaceChildren(fragment);
    historyEmpty.hidden = !!entries.length;
  };

  const currentBookmarkFolder = () =>
    bookmarkFolderStack.at(-1) ?? {
      id: "root",
      title: t("library.bookmarks"),
    };

  const hideBookmarkEditor = () => {
    bookmarkEditor.hidden = true;
    bookmarkEditor.reset();
    bookmarkEditId.value = "";
    bookmarkEditType.value = "";
  };

  const showBookmarkEditor = ({
    id = "",
    type = "bookmark",
    title = "",
    url = "",
  } = {}) => {
    bookmarkEditId.value = id;
    bookmarkEditType.value = type;
    bookmarkEditTitle.value = title;
    bookmarkEditUrl.value = url;
    bookmarkEditUrlLabel.hidden = type === "folder";
    let editorTitleId;
    if (id) {
      editorTitleId =
        type === "folder" ? "bookmarks.editFolder" : "bookmarks.editBookmark";
    } else {
      editorTitleId =
        type === "folder" ? "bookmarks.newFolder" : "bookmarks.newBookmark";
    }
    bookmarkEditorTitle.textContent = t(editorTitleId);
    bookmarkEditor.hidden = false;
    bookmarkEditTitle.focus();
    bookmarkEditTitle.select();
  };

  const renderBookmarks = async () => {
    const identity = ++bookmarkRenderIdentity;
    const folder = currentBookmarkFolder();
    const entries = await runtime.listBookmarks(folder.id);
    if (identity !== bookmarkRenderIdentity) {
      return;
    }
    bookmarkUp.textContent = bookmarkFolderStack.length
      ? t("bookmarks.backFrom", { folder: folder.title })
      : t("bookmarks.root");
    bookmarkUp.disabled = bookmarkFolderStack.length === 0;
    const fragment = document.createDocumentFragment();
    entries.forEach((entry, index) => {
      const row = document.createElement("div");
      row.className = "library-row";
      const primary = document.createElement("button");
      primary.className = "library-primary";
      primary.type = "button";
      primary.title = entry.url || entry.title;
      const title = document.createElement("strong");
      title.textContent =
        entry.title || entry.url || t("common.untitledFolder");
      const detail = document.createElement("small");
      detail.textContent =
        entry.type === "folder" ? t("common.folder") : entry.url;
      primary.append(title, detail);
      primary.addEventListener("click", () => {
        if (entry.type === "folder") {
          bookmarkFolderStack.push({
            id: entry.id,
            title: entry.title || t("common.untitledFolder"),
          });
          hideBookmarkEditor();
          renderBookmarks().catch(console.error);
        } else {
          activeRecord?.session.loadUri(entry.url);
          setLibraryOpen(false);
        }
      });

      const actions = document.createElement("div");
      actions.className = "library-row-actions";
      const moveUp = makeLibraryAction(
        "up",
        t("bookmarks.moveUp", { name: title.textContent }),
        async () => {
          await runtime.moveBookmark(entry.id, folder.id, index - 1);
          await Promise.all([renderBookmarks(), renderBookmarkBar()]);
        },
      );
      moveUp.disabled = index === 0;
      const moveDown = makeLibraryAction(
        "down",
        t("bookmarks.moveDown", { name: title.textContent }),
        async () => {
          await runtime.moveBookmark(entry.id, folder.id, index + 1);
          await Promise.all([renderBookmarks(), renderBookmarkBar()]);
        },
      );
      moveDown.disabled = index === entries.length - 1;
      actions.append(
        moveUp,
        moveDown,
        makeLibraryAction(
          "edit",
          t("bookmarks.editNamed", { name: title.textContent }),
          () => {
            showBookmarkEditor(entry);
          },
        ),
        makeLibraryAction(
          "trash",
          t("bookmarks.deleteNamed", { name: title.textContent }),
          async () => {
            await runtime.removeBookmark(entry.id);
            await Promise.all([renderBookmarks(), renderBookmarkBar()]);
          },
        ),
      );
      row.append(primary, actions);
      fragment.append(row);
    });
    bookmarksList.replaceChildren(fragment);
    bookmarksEmpty.hidden = !!entries.length;
  };

  const closeBookmarkPopup = () => {
    bookmarkBarPopup.hidden = true;
    bookmarkBarPopup.replaceChildren();
    bookmarkBarOverflow.setAttribute("aria-expanded", "false");
    for (const button of bookmarkBarItems.children) {
      button.setAttribute("aria-expanded", "false");
    }
  };

  const openBookmarkEntry = (entry) => {
    if (entry.type === "folder") {
      return;
    }
    activeRecord?.session.loadUri(entry.url);
    closeBookmarkPopup();
  };

  const editBookmarkEntry = (entry) => {
    bookmarkFolderStack = [];
    setLibraryOpen(true, "bookmarks");
    showBookmarkEditor(entry);
  };

  const showBookmarkItemContextMenu = (entry, event) => {
    openGeckoContextMenu({
      ownerId: `bookmark-${entry.id}`,
      x: event.clientX,
      y: event.clientY,
      triggerEvent: event,
      label: t("menu.bookmarkContext"),
      items: [
        { id: "bookmark-open", group: "bookmark-open", enabled: true },
        { id: "bookmark-open-new-tab", group: "bookmark-open", enabled: entry.type !== "folder" && !!entry.url },
        { id: "bookmark-copy-link", group: "bookmark-open", enabled: entry.type !== "folder" && !!entry.url },
        { id: "bookmark-edit", group: "bookmark-manage", enabled: true },
        { id: "bookmark-delete", group: "bookmark-manage", enabled: true },
        { id: "bookmark-add-page", group: "bookmark-create", enabled: true },
        { id: "bookmark-add-folder", group: "bookmark-create", enabled: true },
        { id: "bookmark-manager", group: "bookmark-manage", enabled: true },
      ],
      onSelect: (command) => {
        if (command === "bookmark-open") {
          if (entry.type === "folder") {
            openBookmarkFolder(entry).catch(console.error);
          } else {
            openBookmarkEntry(entry);
          }
        } else if (command === "bookmark-open-new-tab") {
          createSession(entry.url);
        } else if (command === "bookmark-copy-link") {
          navigator.clipboard?.writeText(entry.url).catch(console.error);
        } else if (command === "bookmark-edit") {
          editBookmarkEntry(entry);
        } else if (command === "bookmark-delete") {
          runtime
            .removeBookmark(entry.id)
            .then(() => Promise.all([renderBookmarks(), renderBookmarkBar()]))
            .catch(console.error);
        } else if (command === "bookmark-manager") {
          loadProductPage("navis://bookmarks/");
        } else if (command === "bookmark-add-page" || command === "bookmark-add-folder") {
          bookmarkFolderStack = [];
          setLibraryOpen(true, "bookmarks");
          showBookmarkEditor({ type: command === "bookmark-add-folder" ? "folder" : "bookmark" });
        }
      },
      restoreFocus: () => event.currentTarget?.focus(),
    });
  };

  const makeBookmarkSurfaceButton = (entry, { popup = false } = {}) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = popup ? "menu-item" : "bookmark-bar-item";
    if (popup) {
      button.setAttribute("role", "menuitem");
    }
    const label = entry.title || entry.url || t("common.untitledFolder");
    button.title = entry.url || label;
    button.setAttribute("aria-label", label);
    if (entry.type === "folder") {
      button.setAttribute("aria-haspopup", "menu");
      button.setAttribute("aria-expanded", "false");
    }
    setIcon(button, entry.type === "folder" ? "folder" : "globe");
    const text = document.createElement("span");
    text.textContent = label;
    button.append(text);
    button.addEventListener("click", () => {
      if (entry.type === "folder") {
        openBookmarkFolder(entry, button).catch(console.error);
      } else {
        openBookmarkEntry(entry);
      }
    });
    button.addEventListener("contextmenu", (event) => {
      event.preventDefault();
      event.stopPropagation();
      showBookmarkItemContextMenu(entry, event);
    });
    return button;
  };

  const showBookmarkPopupEntries = (
    entries,
    owner = null,
    anchorRect = null,
  ) => {
    closeCompetingTransientSurfaces("bookmark-popup", "competing-surface");
    const fragment = document.createDocumentFragment();
    for (const entry of entries) {
      fragment.append(makeBookmarkSurfaceButton(entry, { popup: true }));
    }
    if (!entries.length) {
      const empty = document.createElement("p");
      empty.className = "library-empty";
      empty.textContent = t("bookmarks.folderEmpty");
      fragment.append(empty);
    }
    bookmarkBarPopup.replaceChildren(fragment);
    bookmarkBarPopup.hidden = false;
    positionAnchoredSurface(bookmarkBarPopup, owner, {
      align: "start",
      anchorRect,
    });
    owner?.setAttribute("aria-expanded", "true");
    bookmarkBarPopup.querySelector("button")?.focus();
  };

  async function openBookmarkFolder(entry, owner = null) {
    const anchorRect = owner?.getBoundingClientRect() ?? null;
    const entries = await runtime.listBookmarks(entry.id);
    showBookmarkPopupEntries(entries, owner, anchorRect);
  }

  const layoutBookmarkBarOverflow = () => {
    if (bookmarkBar.hidden) {
      return;
    }
    bookmarkBarOverflow.hidden = true;
    bookmarkOverflowEntries = [];
    const buttons = [...bookmarkBarItems.children];
    for (const button of buttons) {
      button.hidden = false;
    }
    if (bookmarkBarItems.scrollWidth <= bookmarkBarItems.clientWidth) {
      return;
    }
    bookmarkBarOverflow.hidden = false;
    const available = bookmarkBarItems.clientWidth;
    const overflowed = [];
    for (const [index, button] of buttons.entries()) {
      if (button.offsetLeft + button.offsetWidth > available) {
        button.hidden = true;
        overflowed.push(bookmarkBarEntries[index]);
      }
    }
    bookmarkOverflowEntries = overflowed;
  };

  const renderBookmarkBar = async () => {
    const identity = ++bookmarkBarRenderIdentity;
    const entries = await runtime.listBookmarks("root");
    if (identity !== bookmarkBarRenderIdentity) {
      return;
    }
    bookmarkBarEntries = entries;
    const fragment = document.createDocumentFragment();
    for (const entry of entries) {
      fragment.append(makeBookmarkSurfaceButton(entry));
    }
    bookmarkBarItems.replaceChildren(fragment);
    bookmarkBarEmpty.hidden = !!entries.length;
    window.requestAnimationFrame(layoutBookmarkBarOverflow);
  };

  const renderBookmarkBarVisibility = () => {
    const mode = runtime.appearanceSettings.bookmarkBar;
    const visible = mode === "always" ||
      (mode === "newtab" && activeRecord?.session.state.identityKey === "newtab");
    const changed = bookmarkBar.hidden === visible;
    bookmarkBar.hidden = !visible;
    if (visible && changed) {
      renderBookmarkBar().catch(console.error);
    } else if (!visible) {
      closeBookmarkPopup();
    }
    return visible;
  };

  const renderCredentials = async () => {
    const identity = ++credentialRenderIdentity;
    const entries = privateMode ? [] : await runtime.listCredentials();
    if (identity !== credentialRenderIdentity) {
      return;
    }
    const availableToSession = new Set(
      activeRecord?.session.state.credentials.items.map((item) => item.id) ??
        [],
    );
    const fragment = document.createDocumentFragment();
    for (const entry of entries) {
      const row = document.createElement("div");
      row.className = "library-row";
      row.dataset.credentialId = entry.id;

      const primary = document.createElement("div");
      primary.className = "library-primary";
      const title = document.createElement("strong");
      title.textContent = entry.username || t("common.noUsername");
      const detail = document.createElement("small");
      detail.textContent = entry.origin;
      primary.append(title, detail);
      if (revealedCredential?.id === entry.id) {
        const password = document.createElement("code");
        password.className = "credential-password";
        password.textContent = revealedCredential.password;
        password.setAttribute(
          "aria-label",
          t("passwords.revealedFor", {
            identity: entry.username || entry.origin,
          }),
        );
        primary.append(password);
      }

      const labelIdentity = entry.username || entry.origin;
      const actions = document.createElement("div");
      actions.className = "library-row-actions";
      const fill = makeLibraryAction(
        "password",
        t("passwords.fillFor", { identity: labelIdentity }),
        async () => {
          try {
            const filled = await activeRecord?.session.fillCredential(entry.id);
            showTransientStatus(
              t(filled ? "passwords.filled" : "passwords.fieldUnavailable"),
            );
          } catch (error) {
            showTransientStatus(t("passwords.fillFailed"));
          }
        },
      );
      fill.disabled = privateMode || !availableToSession.has(entry.id);

      const revealed = revealedCredential?.id === entry.id;
      const reveal = makeLibraryAction(
        revealed ? "conceal" : "reveal",
        t(revealed ? "passwords.hideFor" : "passwords.showFor", {
          identity: labelIdentity,
        }),
        async () => {
          try {
            revealedCredential = revealed
              ? null
              : await runtime.revealCredential(entry.id, { window });
            await renderCredentials();
          } catch (error) {
            if (error.name !== "NS_ERROR_ABORT") {
              console.error("Navis password reveal failed", error);
              showTransientStatus(t("passwords.revealFailed"));
            }
          }
        },
      );

      const remove = makeLibraryAction(
        "trash",
        t("passwords.deleteFor", { identity: labelIdentity }),
        async () => {
          await runtime.removeCredential(entry.id);
          if (revealedCredential?.id === entry.id) {
            revealedCredential = null;
          }
          await renderCredentials();
          showTransientStatus(t("passwords.deleted"));
        },
      );
      actions.append(fill, reveal, remove);
      row.append(primary, actions);
      fragment.append(row);
    }
    passwordsList.replaceChildren(fragment);
    passwordsPrivateNote.hidden = !privateMode;
    passwordsEmpty.hidden = privateMode || !!entries.length;
    clearPasswords.hidden = privateMode;
    clearPasswords.disabled = entries.length === 0;
  };

  const setLibraryView = async (view) => {
    const historySelected = view === "history";
    const bookmarksSelected = view === "bookmarks";
    const passwordsSelected = view === "passwords";
    if (!historySelected && !bookmarksSelected && !passwordsSelected) {
      throw new TypeError(`Unknown library view: ${view}`);
    }
    libraryView = view;
    libraryPanel.dataset.view = view;
    const title = t(`library.${view}`);
    libraryTitle.textContent = title;
    const openLabel = t("library.openFullPage", { section: title });
    openLibraryPage.title = openLabel;
    openLibraryPage.setAttribute("aria-label", openLabel);
    libraryToggle.setAttribute(
      "aria-expanded",
      String(historySelected && !libraryPanel.hidden),
    );
    passwordToggle.setAttribute(
      "aria-expanded",
      String(passwordsSelected && !libraryPanel.hidden),
    );
    historyView.hidden = !historySelected;
    bookmarksView.hidden = !bookmarksSelected;
    passwordsView.hidden = !passwordsSelected;
    if (historySelected) {
      await renderHistory();
    } else if (bookmarksSelected) {
      await renderBookmarks();
    } else if (passwordsSelected) {
      await renderCredentials();
    }
  };

  const setLibraryOpen = (shouldOpen, view = null) => {
    const targetView = view ?? libraryView;
    libraryPanel.hidden = !shouldOpen;
    libraryToggle.setAttribute(
      "aria-expanded",
      String(shouldOpen && targetView === "history"),
    );
    passwordToggle.setAttribute(
      "aria-expanded",
      String(shouldOpen && targetView === "passwords"),
    );
    viewHost.parentElement.dataset.libraryOpen = String(shouldOpen);
    if (!shouldOpen) {
      discardRevealedCredential();
      return;
    }
    closeCompetingTransientSurfaces(null, "persistent-surface-opened");
    setLibraryView(targetView).catch((error) => {
      console.error("Navis could not render the library", error);
      showTransientStatus(t("library.renderFailed"));
    });
  };

  const beginCurrentBookmark = () => {
    const state = activeRecord?.session.state;
    if (!state) {
      return;
    }
    setLibraryOpen(true, "bookmarks");
    showBookmarkEditor({
      type: "bookmark",
      title: state.title,
      url: state.url,
    });
  };

  const tabMemoryText = (usage) =>
    usage?.available
      ? t("tabs.memoryUsage", { memory: formatBytes(usage.bytes) })
      : t("tabs.memoryUnavailable");

  const positionTabHoverCard = (record) => {
    const bounds = record.tab.getBoundingClientRect();
    const cardWidth = Math.min(300, Math.max(0, window.innerWidth - 16));
    const left = Math.min(
      Math.max(8, window.innerWidth - cardWidth - 8),
      Math.max(8, bounds.left + (bounds.width - cardWidth) / 2),
    );
    tabHoverCard.style.left = `${Math.round(left)}px`;
    tabHoverCard.style.top = `${Math.round(bounds.bottom + 6)}px`;
  };

  const renderTabHoverCard = (record) => {
    const { state } = record.session;
    tabHoverTitle.textContent = state.title || t("chrome.newTab");
    tabHoverDomain.textContent =
      state.baseDomain || state.url || t("common.unavailable");
    tabHoverMemory.textContent = record.memoryUsage
      ? tabMemoryText(record.memoryUsage)
      : t("tabs.memoryCalculating");
  };

  const hideTabHoverCard = () => {
    clearTimeout(tabHoverTimer);
    tabHoverTimer = null;
    tabHoverRequestId++;
    tabHoverRecord?.tab?.removeAttribute("aria-describedby");
    tabHoverRecord = null;
    tabHoverCard.hidden = true;
  };

  const showTabHoverCard = async (record) => {
    if (tabHoverRecord !== record || !records.has(record.session.id)) {
      return;
    }
    tabHoverTimer = null;
    const cacheIsFresh =
      record.memoryUsage &&
      Date.now() - record.memoryMeasuredAt < TAB_MEMORY_CACHE_MS;
    if (!cacheIsFresh) {
      record.memoryUsage = null;
    }
    renderTabHoverCard(record);
    positionTabHoverCard(record);
    tabHoverCard.hidden = false;
    record.tab.setAttribute("aria-describedby", "tab-hover-card");
    if (cacheIsFresh) {
      return;
    }
    const requestId = ++tabHoverRequestId;
    const usage = await runtime.measureSessionMemoryUsage(record.session);
    if (
      requestId !== tabHoverRequestId ||
      tabHoverRecord !== record ||
      !records.has(record.session.id)
    ) {
      return;
    }
    record.memoryUsage = usage;
    record.memoryMeasuredAt = Date.now();
    tabHoverMemory.textContent = tabMemoryText(usage);
  };

  const scheduleTabHoverCard = (record) => {
    hideTabHoverCard();
    tabHoverRecord = record;
    tabHoverTimer = setTimeout(() => {
      showTabHoverCard(record).catch(() => {
        if (tabHoverRecord === record) {
          tabHoverMemory.textContent = t("tabs.memoryUnavailable");
        }
      });
    }, TAB_HOVER_DELAY_MS);
  };

  const renderSessionTab = (record) => {
    const { state } = record.session;
    const sharing = Object.entries(record.media).some(
      ([key, active]) => key !== "sessionId" && active,
    );
    record.title.textContent = state.title || t("chrome.newTab");
    record.tab.classList.toggle("is-loading", hasVisibleLoading(state));
    record.tab.classList.toggle("is-crashed", state.crashed);
    record.tab.classList.toggle("has-prompt", Boolean(state.prompt));
    record.tab.classList.toggle("is-sharing", sharing);
    let icon = "globe";
    let indicatorTitle = "";
    if (sharing) {
      icon = "sharing";
      indicatorTitle = t("tabs.media");
    } else if (state.prompt) {
      icon = "prompt";
      indicatorTitle = t("tabs.permission");
    } else if (state.crashed) {
      icon = "warning";
      indicatorTitle = t("tabs.crashedTitle");
    } else if (hasVisibleLoading(state)) {
      icon = "loading";
      indicatorTitle = t("common.loading");
    } else if (state.identity === "internal-page") {
      icon = "navis";
      indicatorTitle = t("tabs.internal");
    } else if (state.identity === "built-in-extension") {
      icon = "extension";
      indicatorTitle = t("tabs.extension");
    } else if (state.identity === "internal-error") {
      icon = "warning";
      indicatorTitle = t("tabs.error");
    }
    const useFavicon = icon === "globe" && Boolean(state.favicon);
    setIcon(record.indicator, icon);
    record.indicator.hidden = useFavicon;
    record.favicon.hidden = !useFavicon;
    if (useFavicon && record.favicon.dataset.source !== state.favicon) {
      record.favicon.src = state.favicon;
      record.favicon.dataset.source = state.favicon;
    } else if (!state.favicon && record.favicon.dataset.source) {
      record.favicon.removeAttribute("src");
      delete record.favicon.dataset.source;
    }
    record.indicatorHost.setAttribute("aria-label", indicatorTitle);
    record.tab.removeAttribute("title");
    const tabLabel = [
      state.title || t("chrome.newTab"),
      hasVisibleLoading(state) ? t("tabs.loading") : "",
      state.crashed ? t("tabs.crashed") : "",
    ]
      .filter(Boolean)
      .join(", ");
    record.tab.setAttribute("aria-label", tabLabel);
    if (tabHoverRecord === record && !tabHoverCard.hidden) {
      renderTabHoverCard(record);
      positionTabHoverCard(record);
    }
  };

  const renderSessionCount = () => {
    const count = records.size;
    const label = t("tabs.count", { count });
    sessionCount.textContent = label;
    sessionCount.setAttribute("aria-label", label);
  };

  const renderPermission = (state) => {
    const permissionRequest =
      state.prompt?.category === "permission" ||
      state.prompt?.category === "media" ||
      state.prompt?.category === "extension-permission"
        ? state.prompt
        : null;
    const opening =
      permissionPanel.hidden && permissionRequest && !state.crashed;
    permissionPanel.hidden = !permissionRequest || state.crashed;
    if (!permissionRequest || state.crashed) {
      return;
    }
    if (opening) {
      closeCompetingTransientSurfaces("permission", "permission-opened");
    }

    const extensionRequest =
      permissionRequest.category === "extension-permission";
    permissionPanel.toggleAttribute("data-extension", extensionRequest);
    if (extensionRequest) {
      permissionOrigin.textContent = t("permission.extensionSource", {
        id: permissionRequest.extensionId,
      });
      permissionTitle.textContent = t("permission.extensionTitle");
      permissionMessage.textContent = t("permission.extensionRequest", {
        name: permissionRequest.extensionName,
      });
      const details = [];
      if (permissionRequest.permissions.length) {
        details.push(
          t("permission.extensionApiDetail", {
            permissions: new Intl.ListFormat(localizer.locale, {
              style: "long",
              type: "conjunction",
            }).format(permissionRequest.permissions),
          }),
        );
      }
      if (permissionRequest.origins.length) {
        details.push(
          t("permission.extensionOriginDetail", {
            origins: new Intl.ListFormat(localizer.locale, {
              style: "long",
              type: "conjunction",
            }).format(permissionRequest.origins),
          }),
        );
      }
      permissionDetail.hidden = !details.length;
      permissionDetail.textContent = details.join("\n");
      blockPermission.textContent = t("permission.extensionDeny");
      allowAlways.textContent = t("permission.extensionAllow");
      allowAlways.hidden = false;
      allowSession.hidden = true;
      return;
    }

    permissionOrigin.textContent =
      permissionRequest.origin || t("prompt.unknownOrigin");
    permissionTitle.textContent = t("permission.title");
    blockPermission.textContent = t("permission.block");
    allowAlways.textContent = t("permission.alwaysAllow");
    allowAlways.hidden = false;
    allowSession.textContent = t("permission.allowSession");
    allowSession.hidden = false;
    const permissionNames = permissionRequest.permissions.map((permission) =>
      t(
        {
          camera: "permission.camera",
          microphone: "permission.microphone",
          screen: "permission.screen",
          location: "permission.location",
          notifications: "permission.notifications",
          "persistent storage": "permission.persistentStorage",
        }[permission] ?? "permission.device",
      ),
    );
    const requested = new Intl.ListFormat(localizer.locale, {
      style: "long",
      type: "conjunction",
    }).format(permissionNames);
    permissionMessage.textContent = t("permission.request", {
      origin: permissionRequest.origin,
      devices: requested,
    });

    const deviceNames = [];
    if (permissionRequest.devices?.camera?.length) {
      deviceNames.push(
        t("permission.cameraDetail", {
          name: permissionRequest.devices.camera[0],
        }),
      );
    }
    if (permissionRequest.devices?.microphone?.length) {
      deviceNames.push(
        t("permission.microphoneDetail", {
          name: permissionRequest.devices.microphone[0],
        }),
      );
    }
    permissionDetail.hidden = !deviceNames.length;
    permissionDetail.textContent = deviceNames.join(" · ");
  };

  const defaultPromptButtonLabel = (request, button) => {
    if (request.category === "credential") {
      if (button.role === "accept") {
        return t(
          request.kind === "password-update"
            ? "passwordPrompt.updateAction"
            : "passwordPrompt.saveAction",
        );
      }
      if (button.role === "cancel") {
        return t("passwordPrompt.notNow");
      }
    }
    if (request.kind === "before-unload") {
      if (button.role === "accept") {
        return t("prompt.leave");
      }
      if (button.role === "cancel") {
        return t("prompt.stay");
      }
    }
    if (request.kind === "auth") {
      if (button.role === "accept") {
        return t("prompt.signIn");
      }
      if (button.role === "cancel") {
        return t("common.cancel");
      }
    }
    if (button.label) {
      return button.label;
    }
    if (button.role === "accept") {
      return t("common.ok");
    }
    if (button.role === "cancel") {
      return t("common.cancel");
    }
    return t("prompt.option", { number: button.id + 1 });
  };

  const webAuthnMessage = (request) => {
    const key =
      {
        presence: "webauthn.presence",
        "attestation-consent": "webauthn.attestation",
        "pin-required": "webauthn.pinRequired",
        "select-sign-result": "webauthn.selectAccount",
        "already-registered": "webauthn.alreadyRegistered",
        "select-device": "webauthn.selectDevice",
        "pin-auth-blocked": "webauthn.pinAuthBlocked",
        "uv-blocked": "webauthn.userVerificationBlocked",
        "device-blocked": "webauthn.deviceBlocked",
        "pin-not-set": "webauthn.pinNotSet",
        "selected-device": "webauthn.selectedDevice",
        "listen-success": "webauthn.listening",
        "listen-error": "webauthn.error",
        "unknown-error": "webauthn.error",
        "pin-is-too-long": "webauthn.pinTooLong",
        "pin-is-too-short": "webauthn.pinTooShort",
        "conditional-get": "webauthn.conditionalGet",
        "related-origin-create": "webauthn.relatedOriginCreate",
        "related-origin-use": "webauthn.relatedOriginUse",
      }[request.kind] ?? "webauthn.error";
    if (request.kind === "pin-invalid" || request.kind === "uv-invalid") {
      return Number.isInteger(request.retries)
        ? t(
            request.kind === "pin-invalid"
              ? "webauthn.pinInvalidRetries"
              : "webauthn.userVerificationInvalidRetries",
            { count: request.retries },
          )
        : t(
            request.kind === "pin-invalid"
              ? "webauthn.pinInvalid"
              : "webauthn.userVerificationInvalid",
          );
    }
    if (request.kind.startsWith("related-origin-")) {
      return t(key, { origin: request.host, rpId: request.rpId });
    }
    return t(key);
  };

  const webAuthnActionLabel = (action, request) =>
    t(
      {
        cancel: "common.cancel",
        submit: "webauthn.submitPin",
        select: "webauthn.useAccount",
        "allow-identifying-attestation": "webauthn.allowIdentification",
        "continue-anonymized": "webauthn.continueAnonymized",
        continue:
          request.kind === "conditional-get"
            ? "webauthn.useAnother"
            : "common.continue",
      }[action] ?? "common.cancel",
    );

  const resetPromptInputs = () => {
    promptTextLabel.hidden = true;
    promptUsernameLabel.hidden = true;
    promptPasswordLabel.hidden = true;
    promptChoiceLabel.hidden = true;
    promptCheckboxLabel.hidden = true;
    promptText.value = "";
    promptUsername.value = "";
    promptPassword.value = "";
    promptPassword.autocomplete = "current-password";
    promptPasswordLabelText.textContent = t("prompt.password");
    promptChoice.replaceChildren();
    promptCheckbox.checked = false;
    promptCheckboxText.textContent = "";
  };

  const restoreContentFocus = () => {
    queueMicrotask(() => {
      activeRecord?.session.view?.focus();
    });
  };

  const focusOpenedPrompt = (request) => {
    queueMicrotask(() => {
      if (promptPanel.hidden || promptPanel.dataset.promptId !== request.id) {
        return;
      }
      const target = [
        promptText,
        promptUsername,
        promptPassword,
        promptChoice,
        promptActions.querySelector("button.primary"),
        promptActions.querySelector("button"),
      ].find(
        (element) =>
          element && !element.disabled && !element.closest("[hidden]"),
      );
      target?.focus();
    });
  };

  const renderWebAuthnPrompt = (request) => {
    promptTitle.textContent = t("webauthn.title");
    promptMessage.textContent = webAuthnMessage(request);
    if (promptPanel.dataset.promptId === request.id) {
      return;
    }
    promptPanel.dataset.promptId = request.id;
    resetPromptInputs();

    if (["pin-required", "pin-invalid"].includes(request.kind)) {
      promptPasswordLabel.hidden = false;
      promptPasswordLabelText.textContent = t("webauthn.pin");
      promptPassword.autocomplete = "off";
    } else if (
      ["select-sign-result", "conditional-get"].includes(request.kind) &&
      request.accounts.length
    ) {
      promptChoiceLabel.hidden = false;
      for (const [index, account] of request.accounts.entries()) {
        const option = document.createElement("option");
        option.value = String(index);
        option.textContent =
          account.displayName || account.name || t("webauthn.unknownAccount");
        promptChoice.append(option);
      }
      promptChoice.selectedIndex = 0;
    }

    promptActions.replaceChildren();
    for (const action of request.actions) {
      const button = document.createElement("button");
      button.type = "button";
      button.id = `prompt-action-${action}`;
      button.classList.add("ui-button");
      button.textContent = webAuthnActionLabel(action, request);
      if (
        ["submit", "select", "continue-anonymized"].includes(action) ||
        (action === "continue" && request.kind !== "conditional-get")
      ) {
        button.classList.add("primary");
      }
      button.addEventListener("click", () => {
        answerWebAuthnPrompt(request.id, action);
      });
      promptActions.append(button);
    }
  };

  const renderGenericPrompt = (request) => {
    if (request.category === "credential") {
      const updating = request.kind === "password-update";
      promptTitle.textContent = t(
        updating ? "passwordPrompt.updateTitle" : "passwordPrompt.saveTitle",
      );
      promptMessage.textContent = request.username
        ? t(
            updating
              ? "passwordPrompt.updateNamed"
              : "passwordPrompt.saveNamed",
            { username: request.username },
          )
        : t(
            updating
              ? "passwordPrompt.updateAccount"
              : "passwordPrompt.saveAccount",
          );
    } else if (request.kind === "before-unload") {
      promptTitle.textContent = t("prompt.beforeUnloadTitle");
      promptMessage.textContent = t("prompt.beforeUnloadMessage");
    } else if (request.kind === "auth") {
      promptTitle.textContent = t("prompt.authTitle");
      promptMessage.textContent = t("prompt.authMessage");
    } else {
      promptTitle.textContent = request.title || t("prompt.title");
      promptMessage.textContent = request.message;
    }
    if (promptPanel.dataset.promptId === request.id) {
      return;
    }
    promptPanel.dataset.promptId = request.id;
    resetPromptInputs();

    if (request.input?.type === "text") {
      promptTextLabel.hidden = false;
      promptText.value = request.input.defaultValue;
    } else if (request.input?.type === "credentials") {
      promptUsernameLabel.hidden = request.input.passwordOnly;
      promptPasswordLabel.hidden = false;
      promptUsername.value = request.input.defaultUsername;
    } else if (request.input?.type === "choice") {
      promptChoiceLabel.hidden = false;
      for (const [index, label] of request.input.choices.entries()) {
        const option = document.createElement("option");
        option.value = String(index);
        option.textContent = label;
        promptChoice.append(option);
      }
      promptChoice.selectedIndex = Math.max(request.input.selected, 0);
    }

    if (request.checkbox) {
      promptCheckboxLabel.hidden = false;
      promptCheckbox.checked = request.checkbox.checked;
      promptCheckboxText.textContent = request.checkbox.label;
    }

    promptActions.replaceChildren();
    for (const buttonDefinition of request.buttons) {
      const button = document.createElement("button");
      button.type = "button";
      button.id = `prompt-action-${buttonDefinition.id}`;
      button.classList.add("ui-button");
      button.textContent = defaultPromptButtonLabel(request, buttonDefinition);
      if (buttonDefinition.id === request.defaultButton) {
        button.classList.add("primary");
      }
      button.addEventListener("click", () => {
        answerGenericPrompt(request.id, buttonDefinition.id);
      });
      promptActions.append(button);
    }
  };

  const renderPrompt = (state) => {
    const request = ["prompt", "credential", "webauthn"].includes(
      state.prompt?.category,
    )
      ? state.prompt
      : null;
    const opening = promptPanel.hidden && request && !state.crashed;
    const closing = !promptPanel.hidden && (!request || state.crashed);
    const restoreFocus =
      closing && promptPanel.contains(document.activeElement);
    promptPanel.hidden = !request || state.crashed;
    if (!request || state.crashed) {
      promptPanel.removeAttribute("data-prompt-id");
      promptPanel.removeAttribute("data-category");
      promptPassword.value = "";
      if (restoreFocus) {
        restoreContentFocus();
      }
      return;
    }
    if (opening) {
      closeCompetingTransientSurfaces("prompt", "prompt-opened");
    }

    promptPanel.dataset.category = request.category;
    promptOrigin.textContent = request.origin || t("prompt.unknownOrigin");
    if (request.category === "webauthn") {
      renderWebAuthnPrompt(request);
    } else {
      renderGenericPrompt(request);
    }
    if (opening) {
      focusOpenedPrompt(request);
    }
  };

  const renderMediaIndicator = (media) => {
    const activeMedia = Object.entries(media)
      .filter(([kind, active]) => kind !== "sessionId" && active)
      .map(([kind]) =>
        t(
          {
            camera: "permission.camera",
            microphone: "permission.microphone",
            screen: "permission.screen",
          }[kind] ?? "permission.device",
        ),
      );
    const label = activeMedia.length
      ? t("media.sharing", {
          devices: new Intl.ListFormat(localizer.locale, {
            style: "short",
            type: "conjunction",
          }).format(activeMedia),
        })
      : "";
    mediaIndicator.hidden = !activeMedia.length;
    mediaIndicator.textContent = label;
    if (label) {
      mediaIndicator.setAttribute("aria-label", label);
    } else {
      mediaIndicator.removeAttribute("aria-label");
    }
  };

  const renderCredentialIndicator = (state) => {
    const count = state?.credentials.items.length ?? 0;
    const available = runtime.capabilities.passwordManager && !privateMode;
    passwordToggle.hidden = !available || count === 0;
    passwordToggle.disabled = !available || count === 0;
    passwordToggle.setAttribute(
      "aria-expanded",
      String(!libraryPanel.hidden && libraryView === "passwords"),
    );
    const label = t("passwords.siteCount", { count });
    passwordToggle.title = label;
    passwordToggle.setAttribute("aria-label", label);
    menuPasswords.disabled = !available;
  };

  const pageSubtitle = (state) => {
    if (state.identity === "built-in-extension") {
      return t("siteInfo.extension.subtitle");
    }
    if (state.identity === "internal-page") {
      return state.identityKey ? `navis://${state.identityKey}` : t("app.name");
    }
    try {
      const uri = new URL(state.url);
      return uri.host || state.url;
    } catch {
      return state.url;
    }
  };

  const identityPresentation = (state) => {
    if (state.identity === "internal-page") {
      return {
        icon: "navis",
        toolbarLabel: t("app.name"),
        toolbarTitle: t("siteInfo.internal.toolbarTitle"),
        panelTitle: t("siteInfo.internal.toolbarTitle"),
        status: t("siteInfo.internal.status"),
        description: t("siteInfo.internal.description"),
      };
    }
    if (state.identity === "built-in-extension") {
      return {
        icon: "extension",
        toolbarLabel: t("siteInfo.extension.toolbarLabel"),
        toolbarTitle: t("siteInfo.extension.title"),
        panelTitle: t("siteInfo.extension.title"),
        status: t("siteInfo.extension.status"),
        description: t("siteInfo.extension.description"),
      };
    }
    if (state.identity === "web" && state.identityKey === "local-page") {
      return {
        icon: "siteControls",
        toolbarLabel: t("siteInfo.local.toolbarLabel"),
        toolbarTitle: t("siteInfo.local.toolbarTitle"),
        panelTitle: t("siteInfo.title"),
        status: t("siteInfo.local.status"),
        description: t("siteInfo.local.description"),
      };
    }
    if (state.identity === "internal-error") {
      return {
        icon: "warning",
        toolbarLabel: t("siteInfo.error.toolbarLabel"),
        toolbarTitle: t("siteInfo.error.title"),
        panelTitle: t("siteInfo.error.title"),
        status: t("siteInfo.error.status"),
        description: t("siteInfo.error.description"),
      };
    }

    const connection = {
      secure: {
        icon: "siteControls",
        toolbarLabel: "",
        toolbarTitle: t("siteInfo.secure.toolbarTitle"),
        panelTitle: t("siteInfo.title"),
        status: t("siteInfo.secure.status"),
        description: t("siteInfo.secure.description"),
      },
      broken: {
        icon: "warning",
        toolbarLabel: t("siteInfo.broken.toolbarLabel"),
        toolbarTitle: t("siteInfo.broken.toolbarTitle"),
        panelTitle: t("siteInfo.title"),
        status: t("siteInfo.broken.status"),
        description: t("siteInfo.broken.description"),
      },
      insecure: {
        icon: "warning",
        toolbarLabel: t("siteInfo.insecure.toolbarLabel"),
        toolbarTitle: t("siteInfo.insecure.toolbarTitle"),
        panelTitle: t("siteInfo.title"),
        status: t("siteInfo.insecure.status"),
        description: t("siteInfo.insecure.description"),
      },
      unknown: {
        icon: "info",
        toolbarLabel: "",
        toolbarTitle: t("siteInfo.unknown.toolbarTitle"),
        panelTitle: t("siteInfo.pageTitle"),
        status: t("siteInfo.unknown.status"),
        description: t("siteInfo.unknown.description"),
      },
    };
    return connection[state.security] ?? connection.unknown;
  };

  const appendCertificateField = (label, value) => {
    const row = document.createElement("div");
    row.className = "certificate-field";
    const term = document.createElement("dt");
    term.textContent = label;
    const definition = document.createElement("dd");
    const displayedValue = value || t("common.unavailable");
    definition.textContent = displayedValue;
    row.setAttribute(
      "aria-label",
      t("certificate.field", { label, value: displayedValue }),
    );
    row.append(term, definition);
    certificateFields.append(row);
  };

  const formatCertificateTime = (value) => {
    if (!value) {
      return t("common.unavailable");
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime())
      ? t("common.unavailable")
      : localizer.dateTime(date, {
          dateStyle: "medium",
          timeStyle: "long",
        });
  };

  const renderCertificateDetails = (details) => {
    certificateFields.replaceChildren();
    certificateChain.replaceChildren();
    const certificate = details?.certificate;
    if (!details?.available || !certificate) {
      appendCertificateField(
        t("certificate.status"),
        t("certificate.unavailable"),
      );
      return;
    }
    const connection = details.connection;
    appendCertificateField(t("certificate.subject"), certificate.subjectName);
    appendCertificateField(
      t("certificate.organization"),
      certificate.organization || certificate.commonName,
    );
    appendCertificateField(t("certificate.issuer"), certificate.issuerName);
    appendCertificateField(
      t("certificate.validFrom"),
      formatCertificateTime(certificate.validFrom),
    );
    appendCertificateField(
      t("certificate.validUntil"),
      formatCertificateTime(certificate.validTo),
    );
    appendCertificateField(
      t("certificate.serialNumber"),
      certificate.serialNumber,
    );
    appendCertificateField(
      t("certificate.sha256Fingerprint"),
      certificate.sha256Fingerprint,
    );
    appendCertificateField(
      t("certificate.publicKeyDigest"),
      certificate.sha256SubjectPublicKeyInfoDigest,
    );
    if (connection) {
      appendCertificateField(t("certificate.protocol"), connection.protocol);
      appendCertificateField(t("certificate.cipher"), connection.cipher);
      appendCertificateField(
        t("certificate.keyExchange"),
        connection.keyExchange,
      );
      appendCertificateField(t("certificate.signature"), connection.signature);
    }

    if (!details.certificateChain.length) {
      return;
    }
    const heading = document.createElement("h3");
    heading.textContent = t("certificate.chain");
    certificateChain.append(heading);
    for (const [index, item] of details.certificateChain.entries()) {
      const entry = document.createElement("article");
      entry.className = "certificate-chain-item";
      const certificateName = document.createElement("strong");
      certificateName.textContent =
        item.displayName ||
        item.commonName ||
        item.subjectName ||
        t("certificate.entry", { number: index + 1 });
      const issuer = document.createElement("small");
      issuer.textContent = t("certificate.issuedBy", {
        issuer:
          item.issuerCommonName || item.issuerName || t("common.unavailable"),
      });
      entry.setAttribute(
        "aria-label",
        t("certificate.chainEntry", {
          name: certificateName.textContent,
          issuer: issuer.textContent,
        }),
      );
      entry.append(certificateName, issuer);
      certificateChain.append(entry);
    }
  };

  const currentSecurityDetails = () => {
    const session = activeRecord?.session;
    if (!session) {
      return null;
    }
    const details = session.getSecurityDetails();
    return details.navigationId === session.state.navigationId ? details : null;
  };

  const hideFullscreenHint = () => {
    clearTimeout(fullscreenHintTimer);
    fullscreenHintTimer = null;
    fullscreenHint.hidden = true;
  };

  const showFullscreenHint = (originText, instruction) => {
    fullscreenOrigin.textContent = originText;
    fullscreenInstruction.textContent = instruction;
    fullscreenHint.hidden = false;
    clearTimeout(fullscreenHintTimer);
    fullscreenHintTimer = setTimeout(() => {
      fullscreenHint.hidden = true;
      fullscreenHintTimer = null;
    }, 3000);
  };

  const renderSiteInfo = () => {
    const state = activeRecord?.session.state;
    if (!state) {
      return;
    }
    const navigationChanged = siteInfoNavigationId !== state.navigationId;
    siteInfoNavigationId = state.navigationId;
    const presentation = identityPresentation(state);
    siteInfoPanel.dataset.identity = state.identity;
    siteInfoPanel.dataset.identityKey = state.identityKey;
    siteInfoPanel.dataset.security = state.security;
    setIcon(siteInfoIcon, presentation.icon);
    siteInfoTitle.textContent = presentation.panelTitle;
    siteInfoSubtitle.textContent = pageSubtitle(state);
    siteInfoStatus.textContent = presentation.status;
    siteInfoStatus.setAttribute("aria-label", presentation.status);
    siteInfoDescription.textContent = presentation.description;

    const showSiteData = !["internal-page", "internal-error"].includes(
      state.identity,
    );
    siteDataSection.hidden = !showSiteData;
    if (showSiteData) {
      const siteData = runtime.getSiteDataState(activeRecord.session);
      clearSiteData.disabled = !siteData.available;
      clearSiteData.dataset.origin = siteData.origin;
      siteDataSummary.textContent = siteData.available
        ? t("siteInfo.storedData", { host: siteData.host })
        : t("siteInfo.unavailable");
    } else {
      clearSiteData.disabled = true;
      delete clearSiteData.dataset.origin;
    }

    const details = currentSecurityDetails();
    certificateDetails.hidden = !details?.available || !details.certificate;
    if (navigationChanged) {
      certificatePanel.hidden = true;
      siteInfoSummary.hidden = false;
    } else if (!certificatePanel.hidden) {
      renderCertificateDetails(details);
    }
  };

  const setSiteInfoOpen = (shouldOpen) => {
    if (!shouldOpen || !activeRecord) {
      clearTimeout(clearSiteDataTimer);
      delete clearSiteData.dataset.confirm;
      delete clearSiteData.dataset.confirmOrigin;
      clearSiteData.textContent = t("siteInfo.clearData");
      siteInfoPanel.hidden = true;
      security.setAttribute("aria-expanded", "false");
      certificatePanel.hidden = true;
      siteInfoSummary.hidden = false;
      siteInfoNavigationId = null;
      return;
    }
    closeCompetingTransientSurfaces("site-info", "competing-surface");
    renderSiteInfo();
    siteInfoPanel.hidden = false;
    positionAnchoredSurface(siteInfoPanel, security, { align: "start" });
    security.setAttribute("aria-expanded", "true");
    closeSiteInfo.focus();
  };

  const renderActiveSession = () => {
    renderBookmarkBarVisibility();
    if (!activeRecord) {
      setSiteInfoOpen(false);
      document.documentElement.removeAttribute("data-dom-fullscreen");
      hideFullscreenHint();
      permissionPanel.hidden = true;
      promptPanel.hidden = true;
      mediaIndicator.hidden = true;
      renderCredentialIndicator(null);
      scheduleExtensionActionsRender();
      return;
    }
    const { state } = activeRecord.session;
    const wasFullscreen = document.documentElement.hasAttribute(
      "data-dom-fullscreen",
    );
    document.documentElement.toggleAttribute(
      "data-dom-fullscreen",
      state.fullscreen,
    );
    if (state.fullscreen && !wasFullscreen) {
      let fullscreenPageOrigin;
      try {
        fullscreenPageOrigin = new URL(state.url).origin;
      } catch {
        fullscreenPageOrigin = t("chrome.thisPage");
      }
      showFullscreenHint(fullscreenPageOrigin, t("chrome.fullscreenEsc"));
    } else if (!state.fullscreen && wasFullscreen) {
      hideFullscreenHint();
    }
    if (!addressEditState.preservesInput) {
      address.value = state.url;
      setOmniboxValidity(address);
    }
    back.disabled = state.crashed || !state.canGoBack;
    forward.disabled = state.crashed || !state.canGoForward;
    reload.disabled = false;
    let reloadLabel = t("chrome.reload");
    if (state.crashed) {
      reloadLabel = t("chrome.restoreTab");
    } else if (hasVisibleLoading(state)) {
      reloadLabel = t("chrome.stopLoading");
    }
    reload.title = reloadLabel;
    reload.setAttribute("aria-label", reloadLabel);
    setIcon(
      reload,
      hasVisibleLoading(state) && !state.crashed ? "stop" : "reload",
    );

    security.dataset.state =
      state.identity === "web" && state.identityKey === "local-page"
        ? "local"
        : state.security;
    security.dataset.identity = state.identity;
    security.dataset.identityKey = state.identityKey;
    const presentation = identityPresentation(state);
    setIcon(security, presentation.icon);
    const securityDetails =
      state.identity === "web" && state.security === "secure"
        ? currentSecurityDetails()
        : null;
    const certificateOrganization =
      securityDetails?.available && securityDetails.certificate?.organization
        ? securityDetails.certificate.organization.trim()
        : "";
    security.dataset.certificateOrganization = String(
      Boolean(certificateOrganization),
    );
    securityLabel.textContent =
      certificateOrganization || presentation.toolbarLabel;
    const securityTitle = certificateOrganization
      ? t("siteInfo.organization.toolbarTitle", {
          organization: certificateOrganization,
        })
      : presentation.toolbarTitle;
    security.title = securityTitle;
    security.setAttribute("aria-label", securityTitle);
    if (!siteInfoPanel.hidden) {
      renderSiteInfo();
    }

    loadingState.dataset.loading = String(hasVisibleLoading(state));
    let activityLabel = t("common.ready");
    if (state.crashed) {
      activityLabel = t("chrome.contentProcessCrashed");
    } else if (hasVisibleLoading(state)) {
      activityLabel = t("common.loading");
    }
    if (!transientStatusTimer) {
      loadingState.textContent = activityLabel;
      loadingState.setAttribute("aria-label", activityLabel);
      loadingState.dataset.visible = "false";
    }
    crashPanel.hidden = !state.crashed;
    renderPermission(state);
    renderPrompt(state);
    renderMediaIndicator(activeRecord.media);
    renderCredentialIndicator(state);
    scheduleExtensionActionsRender();
    const productName = t(privateMode ? "app.privateName" : "app.name");
    document.title = state.title
      ? t("chrome.windowTitle", { title: state.title, product: productName })
      : productName;
  };

  const selectSession = (record, { focusContent = false } = {}) => {
    if (!record) {
      return;
    }
    if (record !== activeRecord) {
      closeCompetingTransientSurfaces(null, "session-changed");
      addressEditState.reset();
      runtime.activateSession(record.session);
      activeRecord = record;
      for (const candidate of records.values()) {
        const selected = candidate === record;
        candidate.tab.setAttribute("aria-selected", String(selected));
        candidate.tab.tabIndex = selected ? 0 : -1;
        candidate.panel.hidden = !selected;
      }
      record.tab.scrollIntoView({ block: "nearest", inline: "nearest" });
      renderActiveSession();
      persistSessionState({ immediate: true });
    }
    if (focusContent) {
      record.session.view?.focus();
    }
  };

  const closeSession = (record) => {
    if (!record) {
      return;
    }
    if (tabHoverRecord === record) {
      hideTabHoverCard();
    }
    if (records.size === 1 && records.get(record.session.id) === record) {
      window.close();
      return;
    }
    const orderedRecords = orderedSessionRecords();
    const closedIndex = orderedRecords.indexOf(record);
    const wasActive = record === activeRecord;
    runtime.closeDeveloperTools(record.session).catch((error) => {
      console.error("Navis could not close the Session developer tools", error);
    });
    record.session.close();
    record.tab.remove();
    record.panel.remove();
    records.delete(record.session.id);
    if (wasActive) {
      activeRecord = null;
      const remaining = orderedSessionRecords();
      selectSession(remaining[Math.min(closedIndex, remaining.length - 1)]);
    }
    renderSessionCount();
    persistSessionState({ immediate: true });
  };

  const showTabContextMenu = (record, event) => {
    const orderedRecords = orderedSessionRecords();
    const index = orderedRecords.indexOf(record);
    if (index === -1) {
      return;
    }
    openGeckoContextMenu({
      ownerId: `tab-${record.session.id}`,
      x: event.clientX,
      y: event.clientY,
      triggerEvent: event,
      label: t("menu.tabContext"),
      items: [
        { id: "tab-new", group: "tab-new", enabled: true },
        {
          id: "tab-reload",
          group: "tab-session",
          enabled: !record.session.state.crashed,
        },
        { id: "tab-duplicate", group: "tab-session", enabled: true },
        { id: "tab-close", group: "tab-close", enabled: true },
        {
          id: "tab-close-others",
          group: "tab-close",
          enabled: orderedRecords.length > 1,
        },
        {
          id: "tab-close-right",
          group: "tab-close",
          enabled: index < orderedRecords.length - 1,
        },
      ],
      onSelect: (command) => {
        if (command === "tab-new") {
          openNewTab();
        } else if (command === "tab-reload") {
          record.session.reload();
        } else if (command === "tab-duplicate") {
          createSession(record.session.state.url);
        } else if (command === "tab-close") {
          closeSession(record);
        } else if (command === "tab-close-others") {
          for (const candidate of orderedSessionRecords()) {
            if (candidate !== record) {
              closeSession(candidate);
            }
          }
          selectSession(record);
        } else if (command === "tab-close-right") {
          for (const candidate of orderedRecords.slice(index + 1).reverse()) {
            closeSession(candidate);
          }
        }
      },
      restoreFocus: () => record.tab?.focus(),
    });
  };

  const showOmniboxContextMenu = (event) => {
    const hasSelection =
      Number.isInteger(address.selectionStart) &&
      Number.isInteger(address.selectionEnd) &&
      address.selectionEnd > address.selectionStart;
    const canUndo = document.queryCommandEnabled("undo");
    const canRedo = document.queryCommandEnabled("redo");
    const clipboardAddress = runtime.getClipboardAddressInput();
    let clipboardCommand = null;
    if (clipboardAddress) {
      clipboardCommand =
        clipboardAddress.kind === "url"
          ? "omnibox-paste-and-go"
          : "omnibox-paste-and-search";
    }
    openGeckoContextMenu({
      ownerId: "omnibox",
      x: event.clientX,
      y: event.clientY,
      triggerEvent: event,
      label: t("menu.addressContext"),
      items: [
        { id: "omnibox-undo", group: "omnibox-history", enabled: canUndo },
        { id: "omnibox-redo", group: "omnibox-history", enabled: canRedo },
        { id: "omnibox-cut", group: "omnibox-edit", enabled: hasSelection },
        { id: "omnibox-copy", group: "omnibox-edit", enabled: hasSelection },
        { id: "omnibox-paste", group: "omnibox-edit", enabled: true },
        ...(clipboardCommand
          ? [
              {
                id: clipboardCommand,
                group: "omnibox-edit",
                enabled: true,
              },
            ]
          : []),
        {
          id: "omnibox-select-all",
          group: "omnibox-edit",
          enabled: Boolean(address.value),
        },
      ],
      onSelect: (command) => {
        address.focus();
        if (
          clipboardAddress &&
          (command === "omnibox-paste-and-go" ||
            command === "omnibox-paste-and-search")
        ) {
          cancelExtensionOmnibox();
          address.value = clipboardAddress.text;
          addressEditState.updateInput();
          setOmniboxValidity(address);
          if (loadAddress()) {
            address.blur();
            activeRecord?.session.view?.focus();
          }
          return;
        }
        if (command === "omnibox-select-all") {
          address.select();
          return;
        }
        const editCommand = command.slice("omnibox-".length);
        document.execCommand(editCommand);
      },
      restoreFocus: () => address.focus(),
    });
  };

  const clearTabDropIndicator = () => {
    tabDropTarget?.tab.classList.remove("drop-before", "drop-after");
    tabDropTarget = null;
    tabDropBefore = false;
  };

  const finishTabDrag = () => {
    clearTabDropIndicator();
    if (draggedTabRecord) {
      draggedTabRecord.tab.classList.remove("is-dragging");
      draggedTabRecord.tab.removeAttribute("aria-grabbed");
    }
    draggedTabRecord = null;
  };

  sessionTabs.addEventListener("dragover", (event) => {
    if (!draggedTabRecord) {
      return;
    }
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = "move";
    }
    const candidates = orderedSessionRecords().filter(
      (record) => record !== draggedTabRecord,
    );
    const beforeRecord = candidates.find((record) => {
      const bounds = record.tab.getBoundingClientRect();
      return event.clientX < bounds.left + bounds.width / 2;
    });
    const nextTarget = beforeRecord ?? candidates.at(-1) ?? null;
    const nextBefore = Boolean(beforeRecord);
    if (nextTarget === tabDropTarget && nextBefore === tabDropBefore) {
      return;
    }
    clearTabDropIndicator();
    tabDropTarget = nextTarget;
    tabDropBefore = nextBefore;
    tabDropTarget?.tab.classList.add(
      tabDropBefore ? "drop-before" : "drop-after",
    );
  });

  sessionTabs.addEventListener("dragleave", (event) => {
    if (!sessionTabs.contains(event.relatedTarget)) {
      clearTabDropIndicator();
    }
  });

  sessionTabs.addEventListener("drop", (event) => {
    if (!draggedTabRecord) {
      return;
    }
    event.preventDefault();
    const movedRecord = draggedTabRecord;
    if (tabDropTarget && tabDropTarget !== movedRecord) {
      sessionTabs.insertBefore(
        movedRecord.tab,
        tabDropBefore ? tabDropTarget.tab : tabDropTarget.tab.nextSibling,
      );
    }
    const orderChanged = synchronizeSessionOrder();
    finishTabDrag();
    if (orderChanged) {
      persistSessionState({ immediate: true });
    }
  });

  sessionStrip.addEventListener(
    "wheel",
    (event) => {
      if (
        draggedTabRecord ||
        records.size < 2 ||
        event.ctrlKey ||
        event.metaKey ||
        event.altKey ||
        event.composedPath().some((node) => node?.id === "window-controls")
      ) {
        return;
      }
      const rawDelta =
        Math.abs(event.deltaY) >= Math.abs(event.deltaX)
          ? event.deltaY
          : event.deltaX;
      if (!rawDelta) {
        return;
      }
      event.preventDefault();
      let deltaScale = 1;
      if (event.deltaMode === 1) {
        deltaScale = 16;
      } else if (event.deltaMode === 2) {
        deltaScale = 96;
      }
      tabWheelDelta += rawDelta * deltaScale;
      clearTimeout(tabWheelResetTimer);
      tabWheelResetTimer = setTimeout(() => {
        tabWheelDelta = 0;
        tabWheelResetTimer = null;
      }, 180);
      const now = performance.now();
      if (Math.abs(tabWheelDelta) < 40 || now - lastTabWheelSwitch < 90) {
        return;
      }
      const direction = Math.sign(tabWheelDelta);
      tabWheelDelta = 0;
      lastTabWheelSwitch = now;
      const orderedRecords = orderedSessionRecords();
      const currentIndex = orderedRecords.indexOf(activeRecord);
      const nextIndex = currentIndex + direction;
      if (nextIndex >= 0 && nextIndex < orderedRecords.length) {
        selectSession(orderedRecords[nextIndex], { focusContent: true });
      }
    },
    { passive: false },
  );

  window.addEventListener("NavisBindingTabActivated", (event) => {
    const record = records.get(event.detail.session.id);
    if (record) {
      selectSession(record, { focusContent: true });
    }
  });
  window.addEventListener("NavisBindingTabCloseRequested", (event) => {
    closeSession(records.get(event.detail.session.id));
  });

  const buildSessionTab = (record) => {
    const tab = document.createElement("div");
    tab.className = "session-tab";
    tab.draggable = true;
    tab.setAttribute("role", "tab");
    tab.setAttribute("aria-selected", "false");
    tab.setAttribute("aria-controls", record.panel.id);
    tab.tabIndex = -1;

    const indicator = document.createElement("span");
    indicator.className = "tab-favicon";
    indicator.setAttribute("aria-hidden", "true");
    const indicatorFallback = document.createElement("span");
    indicatorFallback.className = "tab-favicon-fallback";
    setIcon(indicatorFallback, "globe");
    const favicon = document.createElement("img");
    favicon.className = "tab-favicon-image";
    favicon.alt = "";
    favicon.hidden = true;
    indicator.append(indicatorFallback, favicon);

    const title = document.createElement("span");
    title.className = "session-title";
    title.textContent = t("chrome.newTab");

    const closeButton = createIconButton(document, {
      className: "close-session",
      icon: "close",
      label: t("tabs.close"),
    });
    closeButton.draggable = false;

    tab.append(indicator, title, closeButton);
    tab.addEventListener("pointerenter", () => {
      if (!draggedTabRecord) {
        scheduleTabHoverCard(record);
      }
    });
    tab.addEventListener("pointerleave", hideTabHoverCard);
    tab.addEventListener("dragstart", (event) => {
      if (records.size < 2 || closeButton.contains(event.target)) {
        event.preventDefault();
        return;
      }
      hideTabHoverCard();
      finishTabDrag();
      draggedTabRecord = record;
      tab.classList.add("is-dragging");
      tab.setAttribute("aria-grabbed", "true");
      if (event.dataTransfer) {
        event.dataTransfer.effectAllowed = "move";
        event.dataTransfer.setData(
          "text/x-navis-tab",
          String(record.session.id),
        );
      }
      selectSession(record);
    });
    tab.addEventListener("dragend", finishTabDrag);
    tab.addEventListener("click", (event) => {
      if (!closeButton.contains(event.target)) {
        selectSession(record, { focusContent: true });
      }
    });
    tab.addEventListener("contextmenu", (event) => {
      event.preventDefault();
      hideTabHoverCard();
      showTabContextMenu(record, event);
    });
    tab.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        selectSession(record, { focusContent: true });
        return;
      }
      const orderedRecords = orderedSessionRecords();
      const currentIndex = orderedRecords.indexOf(record);
      let target = null;
      if (event.key === "ArrowLeft") {
        target = orderedRecords.at(currentIndex - 1) ?? orderedRecords.at(-1);
      } else if (event.key === "ArrowRight") {
        target = orderedRecords.at((currentIndex + 1) % orderedRecords.length);
      } else if (event.key === "Home") {
        target = orderedRecords[0];
      } else if (event.key === "End") {
        target = orderedRecords.at(-1);
      } else if (event.key === "Delete") {
        event.preventDefault();
        closeSession(record);
        return;
      }
      if (target) {
        event.preventDefault();
        selectSession(target);
        target.tab.focus();
      }
    });
    closeButton.addEventListener("click", (event) => {
      event.stopPropagation();
      closeSession(record);
    });

    record.tab = tab;
    record.title = title;
    record.indicatorHost = indicator;
    record.indicator = indicatorFallback;
    record.favicon = favicon;
    sessionTabs.append(tab);
  };

  function adoptSession(session, { activate = true } = {}) {
    if (records.has(session.id)) {
      const existing = records.get(session.id);
      if (activate) {
        selectSession(existing);
      }
      return existing;
    }

    const record = {
      session,
      panel: viewPanels.get(session.view),
      tab: null,
      title: null,
      indicator: null,
      indicatorHost: null,
      favicon: null,
      memoryUsage: null,
      memoryMeasuredAt: 0,
      media: {
        sessionId: session.id,
        ...session.state.media,
      },
      navigationId: session.state.navigationId,
    };
    if (!record.panel) {
      throw new Error("The Session has no Platform content panel");
    }
    record.panel.id = `navis-session-panel-${session.id}`;
    records.set(session.id, record);
    buildSessionTab(record);
    if (activate) {
      selectSession(record);
    }
    renderSessionTab(record);
    renderSessionCount();
    if (!activate) {
      persistSessionState({ immediate: true });
    }
    return record;
  }

  function createSession(uri = DEFAULT_URI, { activate = true } = {}) {
    const session = runtime.createSession({ view: createView() });
    const record = adoptSession(session, { activate: false });
    session.open(uri);
    if (activate) {
      selectSession(record);
    }
    renderSessionTab(record);
    return record;
  }

  const loadAddress = () => {
    if (!activeRecord) {
      return false;
    }
    const requestedAddress = address.value;
    try {
      if (extensionOmniboxState.active) {
        const choice =
          extensionOmniboxChoices()[extensionOmniboxSelection] || null;
        if (
          runtime.acceptExtensionOmnibox(
            activeRecord.session,
            choice?.content ?? null,
            "currentTab",
          )
        ) {
          addressEditState.accept();
          renderExtensionOmnibox(
            Object.freeze({ active: false, suggestions: Object.freeze([]) }),
          );
          return true;
        }
      }
      const choice = ordinarySuggestionQuery === requestedAddress.trim()
        ? ordinaryOmniboxChoices()[extensionOmniboxSelection] : null;
      if (choice?.kind === "tab") {
        const record = [...records.values()].find(item => String(item.session.id) === choice.id);
        if (record) {
          cancelExtensionOmnibox();
          addressEditState.accept();
          selectSession(record);
          return true;
        }
      }
      const resolved = choice?.kind === "search"
        ? runtime.searchText(choice.text, {privateMode})
        : choice?.url ? {url: choice.url}
          : runtime.resolveAddressInput(requestedAddress, {privateMode});
      activeRecord.session.loadUri(resolved.url);
      cancelExtensionOmnibox();
      addressEditState.accept();
      setOmniboxValidity(address);
      return true;
    } catch (error) {
      addressEditState.reject();
      const message = t("chrome.invalidAddress");
      setOmniboxValidity(address, message);
      showTransientStatus(message);
      return false;
    }
  };

  const reloadOrStop = () => {
    if (!activeRecord) {
      return;
    }
    if (hasVisibleLoading(activeRecord.session.state)) {
      activeRecord.session.stop();
    } else {
      activeRecord.session.reload();
    }
  };

  const answerPrompt = (decision) => {
    const pendingPrompt = activeRecord?.session.state.prompt;
    const pending = permissionResolvers.get(pendingPrompt?.id);
    if (pending?.sessionId === activeRecord?.session.id) {
      permissionResolvers.delete(pendingPrompt.id);
      pending.resolve(decision);
    }
  };

  const answerGenericPrompt = (promptId, button) => {
    const pending = promptResolvers.get(promptId);
    if (!pending || pending.sessionId !== activeRecord?.session.id) {
      return;
    }
    const { request } = pending;
    const response = {
      action: button === 0 ? "accept" : "dismiss",
      button,
    };
    if (request.checkbox) {
      response.checked = promptCheckbox.checked;
    }
    if (button === 0) {
      if (request.input?.type === "text") {
        response.value = promptText.value;
      } else if (request.input?.type === "credentials") {
        response.username = promptUsername.value;
        response.password = promptPassword.value;
      } else if (request.input?.type === "choice") {
        response.selected = promptChoice.selectedIndex;
      }
    }
    promptResolvers.delete(promptId);
    pending.resolve(response);
  };

  const answerWebAuthnPrompt = (promptId, action) => {
    const pending = webAuthnResolvers.get(promptId);
    if (!pending || pending.sessionId !== activeRecord?.session.id) {
      return;
    }
    const response = { action };
    if (action === "submit") {
      response.pin = promptPassword.value;
    } else if (action === "select") {
      response.selected = promptChoice.selectedIndex;
    }
    promptPassword.value = "";
    webAuthnResolvers.delete(promptId);
    pending.resolve(response);
  };

  const dismissActivePrompt = () => {
    const candidate = activeRecord?.session.state.prompt;
    if (candidate?.category === "webauthn") {
      answerWebAuthnPrompt(candidate.id, "cancel");
    } else if (["prompt", "credential"].includes(candidate?.category)) {
      answerGenericPrompt(candidate.id, candidate.cancelButton);
    }
  };

  const formatBytes = (bytes) => {
    if (bytes < 1024) {
      return t("downloads.bytes", { value: localizer.number(bytes) });
    }
    if (bytes < 1024 * 1024) {
      return t("downloads.kilobytes", {
        value: localizer.number(bytes / 1024, {
          minimumFractionDigits: 1,
          maximumFractionDigits: 1,
        }),
      });
    }
    return t("downloads.megabytes", {
      value: localizer.number(bytes / (1024 * 1024), {
        minimumFractionDigits: 1,
        maximumFractionDigits: 1,
      }),
    });
  };

  const downloadStatusText = (download) => {
    switch (download.status) {
      case "pending":
        return t("downloads.starting");
      case "downloading": {
        const transferred = formatBytes(download.currentBytes);
        if (download.totalBytes > 0) {
          const progress =
            download.progress === null
              ? ""
              : t("downloads.progressSuffix", {
                  progress: localizer.number(download.progress),
                });
          return t("downloads.transferred", {
            current: transferred,
            total: formatBytes(download.totalBytes),
            progress,
          });
        }
        return t("downloads.downloaded", { current: transferred });
      }
      case "complete":
        return t("downloads.completeWithSize", {
          size: formatBytes(download.totalBytes || download.currentBytes),
        });
      case "canceled":
        return t("downloads.canceled");
      case "failed":
        return t("downloads.failed");
      default:
        return t("downloads.unknown");
    }
  };

  const performDownloadAction = async (button, failureMessage, action) => {
    if (button.disabled || button.dataset.downloadActionPending === "true") return;
    button.dataset.downloadActionPending = "true";
    button.disabled = true;
    try {
      if (!(await action())) {
        showTransientStatus(failureMessage);
      }
    } catch (error) {
      console.error(failureMessage, error);
      showTransientStatus(failureMessage);
    } finally {
      delete button.dataset.downloadActionPending;
      button.disabled = button.dataset.downloadUnavailable === "true";
    }
  };

  const downloadRows = new Map();
  const visibleDownloadRows = new Set();
  let downloadsRenderFrame = 0;
  let downloadsSeenFrame = 0;
  const downloadsPanelIsForeground = () =>
    !downloadsPanel.hidden && document.visibilityState === "visible" && document.hasFocus();
  const scheduleDownloadsSeen = () => {
    if (downloadsSeenFrame || !downloadsPanelIsForeground()) return;
    downloadsSeenFrame = window.requestAnimationFrame(() => {
      downloadsSeenFrame = 0;
      if (!downloadsPanelIsForeground()) return;
      const seen = [];
      for (const row of visibleDownloadRows) {
        if (row.item.isConnected && downloadRows.get(row.id) === row && row.attentionToken) {
          seen.push({ id: row.downloadId, token: row.attentionToken });
        }
      }
      if (seen.length) runtime.acknowledgeDownloadsSeen(seen, { window });
    });
  };
  const downloadsVisibilityObserver = new IntersectionObserver(entries => {
    for (const entry of entries) {
      const row = downloadRows.get(entry.target.dataset.downloadId);
      if (!row || row.item !== entry.target) continue;
      if (entry.isIntersecting && entry.intersectionRatio > 0) visibleDownloadRows.add(row);
      else visibleDownloadRows.delete(row);
    }
    scheduleDownloadsSeen();
  }, { root: downloadsPanel, threshold: 0 });
  const scheduleDownloadsRender = () => {
    if (downloadsRenderFrame) return;
    downloadsRenderFrame = window.requestAnimationFrame(() => {
      downloadsRenderFrame = 0;
      renderDownloads();
    });
  };

  const renderDownloads = () => {
    const downloads = runtime.downloads.filter(
      (download) => download.private === privateMode,
    );
    const attention = runtime.getDownloadAttention({ privateMode });
    const activeDownloads = downloads.filter(
      (download) => ["pending", "downloading"].includes(download.status),
    );
    const attentionCount = attention.activeCount + attention.unseenCompleted.length;
    downloadsToggle.hidden = attentionCount === 0 && downloadsPanel.hidden;
    downloadCount.textContent = String(attentionCount);
    downloadCount.hidden = attentionCount === 0;
    const knownActive = activeDownloads.filter(
      (download) => Number.isFinite(download.totalBytes) && download.totalBytes > 0,
    );
    const activeCurrentBytes = knownActive.reduce(
      (total, download) => total + download.currentBytes,
      0,
    );
    const activeTotalBytes = knownActive.reduce(
      (total, download) => total + download.totalBytes,
      0,
    );
    const aggregateProgress = activeTotalBytes && knownActive.length === activeDownloads.length
      ? Math.max(
          0,
          Math.min(
            100,
            Math.round((activeCurrentBytes / activeTotalBytes) * 100),
          ),
        )
      : null;
    // SVGElement does not reflect HTMLElement.hidden; change the attribute.
    downloadToolbarProgress.toggleAttribute("hidden", activeDownloads.length === 0);
    downloadToolbarProgress.dataset.indeterminate = String(
      activeDownloads.length > knownActive.length,
    );
    if (aggregateProgress === null) {
      downloadToolbarProgress.style.removeProperty("--download-progress");
    } else {
      downloadToolbarProgress.style.setProperty("--download-progress", String(aggregateProgress));
    }
    let downloadsLabel;
    if (activeDownloads.length && aggregateProgress === null) {
      downloadsLabel = t("downloads.activeCount", {
        count: downloads.length,
        active: activeDownloads.length,
      });
    } else if (activeDownloads.length) {
      downloadsLabel = t("downloads.activeProgress", {
        count: downloads.length,
        active: activeDownloads.length,
        progress: aggregateProgress,
      });
    } else if (downloads.length) {
      downloadsLabel = t("downloads.count", { count: downloads.length });
    } else {
      downloadsLabel = t("downloads.title");
    }
    downloadsToggle.title = downloadsLabel;
    downloadsToggle.setAttribute("aria-label", downloadsLabel);
    if (downloadsPanel.hidden) return;
    downloadsEmpty.hidden = Boolean(downloads.length);
    const existingIds = new Set(downloads.map(download => String(download.id)));
    for (const [id, row] of downloadRows) {
      if (!existingIds.has(id)) {
        downloadsVisibilityObserver.unobserve(row.item);
        visibleDownloadRows.delete(row);
        row.item.remove();
        downloadRows.delete(id);
      }
    }
    let previousItem = null;
    for (const download of [...downloads].reverse()) {
      const id = String(download.id);
      let row = downloadRows.get(id);
      if (!row) {
        const item = document.createElement("div");
        item.className = "download-item";
        item.dataset.downloadId = id;
        item.setAttribute("role", "listitem");
        const details = document.createElement("div");
        details.className = "download-details";
        const fileNameLabel = document.createElement("span");
        fileNameLabel.className = "download-name";
        const statusLabel = document.createElement("span");
        statusLabel.className = "download-status";
        statusLabel.setAttribute("role", "status");
        statusLabel.setAttribute("aria-live", "polite");
        const progress = document.createElement("progress");
        progress.className = "download-progress";
        progress.max = 100;
        details.append(fileNameLabel, statusLabel, progress);
        const actions = document.createElement("div");
        actions.className = "download-actions";
        item.append(details, actions);
        row = { id, downloadId: download.id, item, fileNameLabel, statusLabel, progress, actions, buttons: {}, attentionToken: null };
        downloadRows.set(id, row);
      }
      const { item, fileNameLabel, statusLabel, progress, actions } = row;
      row.download = download;
      const nextItem = previousItem ? previousItem.nextElementSibling : downloadList.firstElementChild;
      if (nextItem !== item) downloadList.insertBefore(item, nextItem);
      previousItem = item;
      row.attentionToken = download.status === "complete" ? download.attentionToken : null;
      downloadsVisibilityObserver.observe(item);
      fileNameLabel.textContent = download.fileName;
      fileNameLabel.title = download.sourceUrl;
      fileNameLabel.setAttribute("aria-label", download.fileName);

      const statusText = downloadStatusText(download);
      if (statusLabel.textContent !== statusText) statusLabel.textContent = statusText;
      statusLabel.setAttribute("aria-label", statusText);
      item.setAttribute(
        "aria-label",
        t("downloads.item", { name: download.fileName, status: statusText }),
      );
      const active = ["pending", "downloading"].includes(download.status);
      progress.hidden = !active;
      if (download.totalBytes > 0 && Number.isFinite(download.progress)) {
        progress.value = Math.max(0, Math.min(100, download.progress));
      } else {
        progress.removeAttribute("value");
      }
      progress.setAttribute("aria-label", t("downloads.progress", { name: download.fileName }));
      for (const [name, offered, execute] of [
        ["open", download.status === "complete", () => runtime.openDownload(row.download.id, { window })],
        ["cancel", download.canCancel, () => runtime.cancelDownload(row.download.id)],
        ["retry", !download.canCancel && download.canRetry, () => runtime.retryDownload(row.download.id)],
        ["remove", !active, () => runtime.removeDownload(row.download.id)],
      ]) {
        let button = row.buttons[name];
        if (!button && offered) {
          button = document.createElement("button");
          button.type = "button";
          button.className = "ui-button";
          button.textContent = t("common." + name);
          button.addEventListener("click", event => {
            if (button.hidden) return;
            if (name === "open" && event?.detail > 1) return;
            return performDownloadAction(button,
              t("downloads." + name + "Failed", { name: row.download.fileName }), execute);
          });
          row.buttons[name] = button;
          actions.append(button);
        }
        if (!button) continue;
        button.hidden = !offered;
        button.dataset.downloadUnavailable = String(name === "open" && !download.canOpen);
        button.disabled = button.dataset.downloadUnavailable === "true" || button.dataset.downloadActionPending === "true";
        button.setAttribute("aria-label", t("downloads." + name, { name: download.fileName }));
      }
    }
    scheduleDownloadsSeen();
  };

  const setDownloadsOpen = (shouldOpen) => {
    downloadsPanel.hidden = !shouldOpen;
    downloadsToggle.setAttribute("aria-expanded", String(shouldOpen));
    if (shouldOpen) {
      closeCompetingTransientSurfaces("downloads", "competing-surface");
      renderDownloads();
      positionAnchoredSurface(downloadsPanel, downloadsToggle);
    } else {
      downloadsVisibilityObserver.disconnect();
      visibleDownloadRows.clear();
      if (downloadsSeenFrame) {
        window.cancelAnimationFrame(downloadsSeenFrame);
        downloadsSeenFrame = 0;
      }
      clearMaterialRipples(downloadsPanel);
      scheduleDownloadsRender();
    }
  };

  closeCompetingTransientSurfaces = (
    exception = null,
    reason = "competing-surface",
  ) => {
    const relatedContextSurface =
      exception === "context-menu" ? contextMenuOwner?.relatedSurface : null;
    const keepsSurface = (surface) =>
      exception === surface || relatedContextSurface === surface;
    if (!keepsSurface("extension-popup") && activeRecord) {
      try {
        runtime.closeExtensionPopup(activeRecord.session);
      } catch (error) {
        console.error("Navis could not close an extension popup", error);
      }
    }
    if (!keepsSurface("context-menu")) {
      contextMenuController.close(reason, { restoreFocus: false });
    }
    if (!keepsSurface("site-info")) {
      setSiteInfoOpen(false);
    }
    if (!keepsSurface("downloads")) {
      setDownloadsOpen(false);
    }
    if (!keepsSurface("extensions")) {
      setExtensionsMenuOpen(false);
    }
    if (!keepsSurface("app-menu")) {
      setAppMenuOpen(false);
    }
    if (!keepsSurface("profile")) {
      setProfileMenuOpen(false);
    }
    if (!keepsSurface("bookmark-popup")) {
      closeBookmarkPopup();
    }
    if (!keepsSurface("omnibox")) {
      cancelExtensionOmnibox();
    }
    if (!keepsSurface("prompt") && !promptPanel.hidden) {
      dismissActivePrompt();
    }
    if (!keepsSurface("permission") && !permissionPanel.hidden) {
      answerPrompt("dismiss");
    }
  };

  const showProductTextContextMenu = (event) => {
    const path = event.composedPath();
    const target = path.find((node) => node?.nodeType === Node.ELEMENT_NODE);
    if (
      target?.closest?.("#bookmark-bar, #bookmark-bar-popup .menu-item")
    ) {
      // Bookmark surfaces own richer Open/Edit/Delete/visibility commands.
      // The document-level text menu runs in capture phase, so it must yield
      // before the dedicated bookmark listener receives the trusted event.
      return false;
    }
    const surface = path.find((node) =>
      node?.matches?.(
        "#library-panel, #downloads-panel, #extensions-menu, #site-info-panel, #app-menu, #bookmark-bar, #bookmark-bar-popup",
      ),
    );
    if (!surface) {
      return false;
    }
    const textControl = target?.closest?.(
      'input:not([type="button"]), textarea',
    );
    const selection = document.getSelection();
    let selectedText = "";
    if (
      textControl &&
      Number.isInteger(textControl.selectionStart) &&
      Number.isInteger(textControl.selectionEnd) &&
      textControl.selectionEnd > textControl.selectionStart
    ) {
      selectedText = textControl.value.slice(
        textControl.selectionStart,
        textControl.selectionEnd,
      );
    } else if (!textControl && selection && !selection.isCollapsed) {
      selectedText = selection.toString();
    }
    const hasSelection = Boolean(selectedText);
    const canSearch = hasSelection && textControl?.type !== "password" && selectedText.length <= 8192;
    const searchProvider = runtime.searchProviders.find(item => item.id === runtime.defaultSearchProvider);
    event.preventDefault();
    event.stopPropagation();
    openGeckoContextMenu({
      ownerId: "product-text",
      x: event.clientX,
      y: event.clientY,
      triggerEvent: event,
      label: t("menu.textContext"),
      items: [
        { id: "product-copy", group: "product-edit", enabled: hasSelection },
        ...(canSearch ? [{id: "search-selection", group: "selection", enabled: true,
          providerName: searchProvider.name,
          excerpt: Array.from(selectedText.trim().replace(/\s+/gu, " ")).slice(0, 40).join("")}] : []),
        {
          id: "product-select-all",
          group: "product-edit",
          enabled: Boolean(textControl?.value || surface.textContent),
        },
      ],
      onSelect: (command) => {
        if (command === "product-copy") {
          runtime.copyText(selectedText);
        } else if (command === "search-selection" && canSearch && activeRecord) {
          runtime.searchSelectedText(activeRecord.session, selectedText, searchProvider.id);
        } else if (command === "product-select-all") {
          if (textControl) {
            textControl.focus();
            textControl.select();
          } else {
            document.getSelection()?.selectAllChildren(surface);
          }
        }
      },
      restoreFocus: () => target?.focus?.(),
    });
    return true;
  };

  runtime.setDownloadDelegate({
    onDownloadsChanged: scheduleDownloadsRender,
  });
  window.addEventListener("focus", scheduleDownloadsRender);
  document.addEventListener("visibilitychange", scheduleDownloadsRender);

  const openNewTab = () => {
    createSession();
    address.focus();
    address.select();
  };

  transientSurfaceForPath = (path) => {
    const matches = (selector, key) =>
      path.some((node) => node?.matches?.(selector)) ? key : null;
    return (
      matches("#context-menu", "context-menu") ||
      matches("#navis-extension-popup", "extension-popup") ||
      matches("#omnibox-shell", "omnibox") ||
      matches("#site-info-panel, #security", "site-info") ||
      matches("#downloads-panel, #downloads-toggle", "downloads") ||
      matches(
        "#extensions-menu, #extensions-menu-toggle, #extension-action-buttons",
        "extensions",
      ) ||
      matches("#app-menu, #app-menu-toggle", "app-menu") ||
      matches("#profile-menu, #profile-toggle, .profile-dialog", "profile") ||
      matches(
        "#bookmark-bar-popup, #bookmark-bar-overflow, .bookmark-bar-item[aria-expanded='true']",
        "bookmark-popup",
      ) ||
      matches(".permission-card", "permission") ||
      matches(".prompt-card", "prompt")
    );
  };

  document.addEventListener(
    "pointerdown",
    (event) => {
      if (event.isTrusted) {
        closeCompetingTransientSurfaces(
          transientSurfaceForPath(event.composedPath()),
          "chrome-interaction",
        );
      }
    },
    true,
  );
  document.addEventListener(
    "focusin",
    (event) => {
      if (event.isTrusted) {
        closeCompetingTransientSurfaces(
          transientSurfaceForPath(event.composedPath()),
          "focus-transfer",
        );
      }
    },
    true,
  );

  window.addEventListener("blur", hideTabHoverCard);
  window.addEventListener("resize", () => {
    if (tabHoverRecord && !tabHoverCard.hidden) {
      positionTabHoverCard(tabHoverRecord);
    }
  });

  newSession.addEventListener("click", () => {
    openNewTab();
  });
  windowMinimize.addEventListener("click", () => window.minimize());
  windowMaximize.addEventListener("click", () => {
    if (window.windowState === window.STATE_MAXIMIZED) {
      window.restore();
    } else {
      window.maximize();
    }
  });
  windowClose.addEventListener("click", () => window.close());
  bookmarkBarOverflow.addEventListener("click", () => {
    if (bookmarkBarPopup.hidden) {
      showBookmarkPopupEntries(bookmarkOverflowEntries, bookmarkBarOverflow);
    } else {
      closeBookmarkPopup();
    }
  });
  bookmarkBar.addEventListener("contextmenu", event => {
    if (!event.isTrusted || event.target.closest(".bookmark-bar-item")) return;
    event.preventDefault();
    event.stopPropagation();
    openGeckoContextMenu({
      ownerId: "bookmark-bar-background", x: event.clientX, y: event.clientY,
      triggerEvent: event, label: t("menu.bookmarkContext"),
      items: [
        { id: "bookmark-add-page", group: "bookmark-create", enabled: true },
        { id: "bookmark-add-folder", group: "bookmark-create", enabled: true },
        { id: "bookmark-manager", group: "bookmark-manage", enabled: true },
      ],
      onSelect: command => {
        if (command === "bookmark-manager") {
          loadProductPage("navis://bookmarks/");
        } else {
          bookmarkFolderStack = [];
          setLibraryOpen(true, "bookmarks");
          showBookmarkEditor({ type: command === "bookmark-add-folder" ? "folder" : "bookmark" });
        }
      },
      restoreFocus: () => appMenuToggle.focus(),
    });
  });
  document.addEventListener(
    "contextmenu",
    (event) => {
      if (!event.isTrusted) {
        return;
      }
      if (event.composedPath().includes(address)) {
        event.preventDefault();
        event.stopPropagation();
        showOmniboxContextMenu(event);
      } else {
        showProductTextContextMenu(event);
      }
    },
    true,
  );
  address.addEventListener("keydown", (event) => {
    if (event.isComposing || addressComposing || event.keyCode === 229) return;
    const choices = extensionOmniboxChoices();
    if (choices.length && ["ArrowDown", "ArrowUp"].includes(event.key)) {
      event.preventDefault();
      const direction = event.key === "ArrowDown" ? 1 : -1;
      extensionOmniboxSelection =
        (extensionOmniboxSelection + direction + choices.length) %
        choices.length;
      renderExtensionOmnibox(extensionOmniboxState);
    } else if (
      choices.length &&
      event.key === "Delete" &&
      event.shiftKey &&
      choices[extensionOmniboxSelection]?.deletable
    ) {
      event.preventDefault();
      runtime.deleteExtensionOmniboxSuggestion(
        activeRecord.session,
        choices[extensionOmniboxSelection].content,
      );
    } else if (event.key === "Enter") {
      event.preventDefault();
      if (loadAddress()) {
        address.blur();
        activeRecord?.session.view?.focus();
      }
    } else if (event.key === "Escape") {
      event.preventDefault();
      cancelExtensionOmnibox();
      addressEditState.reset();
      if (activeRecord) {
        address.value = activeRecord.session.state.url;
        setOmniboxValidity(address);
      }
      address.blur();
      activeRecord?.session.view?.focus();
    }
  });
  address.addEventListener("pointerdown", (event) => {
    selectAddressOnActivationClick =
      event.isPrimary &&
      event.button === 0 &&
      document.activeElement !== address;
  });
  address.addEventListener("click", (event) => {
    if (selectAddressOnActivationClick && event.button === 0) {
      address.select();
    }
    selectAddressOnActivationClick = false;
  });
  address.addEventListener("focus", () => {
    addressEditState.beginEditing();
  });
  address.addEventListener("copy", (event) => {
    const selectsWholeAddress =
      address.selectionStart === 0 &&
      address.selectionEnd === address.value.length;
    if (
      selectsWholeAddress &&
      runtime.cleanLinksEnabled &&
      runtime.copyAddressLink(address.value)
    ) {
      event.preventDefault();
    }
  });
  address.addEventListener("input", () => {
    addressEditState.updateInput();
    setOmniboxValidity(address);
    updateExtensionOmnibox();
  });
  address.addEventListener("compositionstart", () => {
    addressComposing = true;
    cancelExtensionOmnibox();
  });
  address.addEventListener("compositionend", () => {
    addressComposing = false;
    updateExtensionOmnibox();
  });
  address.addEventListener("blur", () => {
    cancelExtensionOmnibox();
    if (!addressEditState.blur()) {
      return;
    }
    renderActiveSession();
  });
  back.addEventListener("click", () => activeRecord?.session.goBack());
  forward.addEventListener("click", () => activeRecord?.session.goForward());
  reload.addEventListener("click", reloadOrStop);
  security.addEventListener("click", () => {
    setSiteInfoOpen(siteInfoPanel.hidden);
  });
  closeSiteInfo.addEventListener("click", () => {
    setSiteInfoOpen(false);
    security.focus();
  });
  certificateDetails.addEventListener("click", () => {
    const details = currentSecurityDetails();
    if (!details?.available || !details.certificate) {
      return;
    }
    renderCertificateDetails(details);
    siteInfoSummary.hidden = true;
    certificatePanel.hidden = false;
    certificateBack.focus();
  });
  certificateBack.addEventListener("click", () => {
    certificatePanel.hidden = true;
    siteInfoSummary.hidden = false;
    certificateDetails.focus();
  });

  bookmarkCurrent.addEventListener("click", beginCurrentBookmark);
  libraryToggle.addEventListener("click", () => {
    const historyIsOpen = !libraryPanel.hidden && libraryView === "history";
    setLibraryOpen(!historyIsOpen, "history");
  });
  closeLibrary.addEventListener("click", () => setLibraryOpen(false));
  openLibraryPage.addEventListener("click", () => {
    loadProductPage(`navis://${libraryView}/`);
  });
  historySearch.addEventListener("input", () => {
    renderHistory().catch(console.error);
  });
  clearHistory.addEventListener("click", async () => {
    if (clearHistory.dataset.confirm !== "true") {
      clearHistory.dataset.confirm = "true";
      clearHistory.textContent = t("history.confirmClear");
      setTimeout(() => {
        delete clearHistory.dataset.confirm;
        clearHistory.textContent = t("history.clear");
      }, 5000);
      return;
    }
    delete clearHistory.dataset.confirm;
    clearHistory.textContent = t("history.clear");
    await runtime.clearHistory();
    await renderHistory();
    showTransientStatus(t("history.cleared"));
  });
  bookmarkUp.addEventListener("click", () => {
    bookmarkFolderStack.pop();
    hideBookmarkEditor();
    renderBookmarks().catch(console.error);
  });
  addCurrentBookmark.addEventListener("click", beginCurrentBookmark);
  addBookmarkFolder.addEventListener("click", () => {
    showBookmarkEditor({ type: "folder" });
  });
  cancelBookmarkEdit.addEventListener("click", hideBookmarkEditor);
  bookmarkEditor.addEventListener("submit", async (event) => {
    event.preventDefault();
    const id = bookmarkEditId.value;
    const type = bookmarkEditType.value || "bookmark";
    try {
      if (id) {
        await runtime.updateBookmark(id, {
          title: bookmarkEditTitle.value,
          ...(type === "bookmark" ? { url: bookmarkEditUrl.value } : {}),
        });
      } else {
        await runtime.createBookmark({
          parentId: currentBookmarkFolder().id,
          type,
          title: bookmarkEditTitle.value,
          url: type === "bookmark" ? bookmarkEditUrl.value : null,
        });
      }
      hideBookmarkEditor();
      await Promise.all([renderBookmarks(), renderBookmarkBar()]);
      showTransientStatus(t(id ? "bookmarks.updated" : "bookmarks.saved"));
    } catch (error) {
      showTransientStatus(t("bookmarks.operationFailed"));
      console.error("Navis bookmark operation failed", error);
    }
  });
  appMenuToggle.addEventListener("click", () => {
    setAppMenuOpen(appMenu.hidden);
  });
  profileToggle.addEventListener("click", () => setProfileMenuOpen(profileMenu.hidden));
  newPrivateWindow.addEventListener("click", () => {
    setAppMenuOpen(false);
    runtime.openPlatformWindow({
      ownerWindow: window,
      uri: document.documentURI,
      privateMode: true,
    });
  });
  const loadProductPage = (uri) => {
    setAppMenuOpen(false);
    setLibraryOpen(false);
    activeRecord?.session.loadUri(uri);
    activeRecord?.session.view?.focus();
  };
  menuHistory.addEventListener("click", () =>
    loadProductPage("navis://history/"),
  );
  menuBookmarks.addEventListener("click", () =>
    loadProductPage("navis://bookmarks/"),
  );
  menuPasswords.addEventListener("click", () =>
    loadProductPage("navis://passwords/"),
  );
  menuDownloads.addEventListener("click", () =>
    loadProductPage("navis://downloads/"),
  );
  menuSettings.addEventListener("click", () =>
    loadProductPage("navis://settings/"),
  );
  menuProcesses.addEventListener("click", () => loadProductPage("navis://processes/"));
  menuProfiles.addEventListener("click", () => loadProductPage("navis://profiles/"));
  menuSupport.addEventListener("click", () => loadProductPage("navis://support/"));
  menuDeveloperTools.addEventListener("click", () => {
    setAppMenuOpen(false);
    toggleActiveDeveloperTools();
  });
  passwordToggle.addEventListener("click", () =>
    setLibraryOpen(true, "passwords"),
  );
  clearPasswords.addEventListener("click", async () => {
    if (clearPasswords.dataset.confirm !== "true") {
      clearPasswords.dataset.confirm = "true";
      clearPasswords.textContent = t("passwords.confirmClear");
      clearTimeout(clearPasswordsTimer);
      clearPasswordsTimer = setTimeout(() => {
        delete clearPasswords.dataset.confirm;
        clearPasswords.textContent = t("passwords.clear");
      }, 5000);
      return;
    }
    clearTimeout(clearPasswordsTimer);
    delete clearPasswords.dataset.confirm;
    clearPasswords.textContent = t("passwords.clear");
    discardRevealedCredential();
    await runtime.clearCredentials();
    await renderCredentials();
    showTransientStatus(t("passwords.cleared"));
  });
  clearSiteData.addEventListener("click", async () => {
    const record = activeRecord;
    const siteData = record ? runtime.getSiteDataState(record.session) : null;
    if (!record || !siteData?.available) {
      showTransientStatus(t("siteInfo.dataUnavailableStatus"));
      return;
    }
    if (clearSiteData.dataset.confirm !== "true") {
      clearSiteData.dataset.confirm = "true";
      clearSiteData.dataset.confirmOrigin = siteData.origin;
      clearSiteData.textContent = t("siteInfo.confirmClear");
      clearTimeout(clearSiteDataTimer);
      clearSiteDataTimer = setTimeout(() => {
        delete clearSiteData.dataset.confirm;
        delete clearSiteData.dataset.confirmOrigin;
        clearSiteData.textContent = t("siteInfo.clearData");
      }, 5000);
      return;
    }
    clearTimeout(clearSiteDataTimer);
    delete clearSiteData.dataset.confirm;
    const confirmedOrigin = clearSiteData.dataset.confirmOrigin;
    delete clearSiteData.dataset.confirmOrigin;
    clearSiteData.textContent = t("siteInfo.clearData");
    try {
      const current = runtime.getSiteDataState(record.session);
      if (
        record !== activeRecord ||
        !current.available ||
        current.origin !== confirmedOrigin
      ) {
        throw new Error("The current site changed before data was cleared");
      }
      await runtime.clearSiteDataForSession(record.session);
      setSiteInfoOpen(false);
      record.session.reload();
      showTransientStatus(t("siteInfo.dataCleared", { host: current.host }));
    } catch (error) {
      showTransientStatus(t("siteInfo.dataClearFailed"));
      console.error("Navis site-data clearing failed", error);
    }
  });
  restoreSession.addEventListener("click", () => {
    activeRecord?.session.restore();
  });
  allowSession.addEventListener("click", () => answerPrompt("allow-session"));
  allowAlways.addEventListener("click", () => answerPrompt("allow-always"));
  blockPermission.addEventListener("click", () => answerPrompt("block"));
  document
    .getElementById("dismiss-permission")
    .addEventListener("click", () => answerPrompt("dismiss"));
  document
    .getElementById("dismiss-prompt")
    .addEventListener("click", dismissActivePrompt);
  downloadsToggle.addEventListener("click", () => {
    setDownloadsOpen(downloadsPanel.hidden);
  });
  document.getElementById("close-downloads").addEventListener("click", () => {
    setDownloadsOpen(false);
  });
  extensionsMenuToggle.addEventListener("click", () => {
    setExtensionsMenuOpen(extensionsMenu.hidden);
  });
  document
    .getElementById("close-extensions-menu")
    .addEventListener("click", () => setExtensionsMenuOpen(false));
  manageExtensions.addEventListener("click", () => {
    setExtensionsMenuOpen(false);
    loadProductPage("navis://extensions/");
  });
  const toggleActiveDeveloperTools = (toolId = null) => {
    if (!activeRecord) {
      return;
    }
    closeCompetingTransientSurfaces(null, "developer-tools-opened");
    const options = toolId ? { toolId } : undefined;
    runtime
      .toggleDeveloperTools(activeRecord.session, options)
      .catch((error) => {
        console.error("Navis could not toggle developer tools", error);
        showTransientStatus(error.message);
      });
  };
  // XUL keys remain active while focus is inside a remote content process.
  // The ordinary chrome-window keydown listener cannot observe that case.
  for (const { element, toolId } of developerToolsShortcutBindings) {
    element.addEventListener("command", (event) => {
      event.preventDefault();
      toggleActiveDeveloperTools(toolId);
    });
  }
  // This is the single keyboard-routing table for chrome and Session actions.
  // eslint-disable-next-line complexity
  window.addEventListener("keydown", (event) => {
    if (
      event.key === "F11" &&
      !event.altKey &&
      !event.ctrlKey &&
      !event.metaKey &&
      !event.shiftKey
    ) {
      event.preventDefault();
      if (activeRecord?.session.state.fullscreen) {
        return;
      }
      const enteringFullscreen = !window.fullScreen;
      if (enteringFullscreen) {
        setSiteInfoOpen(false);
        setDownloadsOpen(false);
        setExtensionsMenuOpen(false);
        setLibraryOpen(false);
        setAppMenuOpen(false);
        setProfileMenuOpen(false);
      }
      window.fullScreen = enteringFullscreen;
      return;
    }
    if (event.key === "F6") {
      event.preventDefault();
      address.focus();
      address.select();
      return;
    }
    if (event.altKey && !event.ctrlKey && !event.metaKey) {
      if (event.key === "ArrowLeft") {
        event.preventDefault();
        activeRecord?.session.goBack();
      } else if (event.key === "ArrowRight") {
        event.preventDefault();
        activeRecord?.session.goForward();
      }
      return;
    }
    if (event.key === "Escape") {
      // Native dialog cancellation owns Escape while editing a profile; do not
      // also dismiss a sidebar or stop the background page's load.
      if (document.querySelector(".profile-dialog[open]")) {
        return;
      }
      if (!profileMenu.hidden) {
        event.preventDefault();
        setProfileMenuOpen(false);
        profileToggle.focus();
        return;
      }
      if (document.activeElement === address) {
        return;
      }
      if (!bookmarkBarPopup.hidden) {
        closeBookmarkPopup();
        bookmarkBarOverflow.focus();
        return;
      }
      if (!siteInfoPanel.hidden) {
        setSiteInfoOpen(false);
        security.focus();
        return;
      }
      if (!appMenu.hidden) {
        setAppMenuOpen(false);
        appMenuToggle.focus();
        return;
      }
      if (!extensionsMenu.hidden) {
        setExtensionsMenuOpen(false);
        extensionsMenuToggle.focus();
        return;
      }
      if (!libraryPanel.hidden) {
        setLibraryOpen(false);
        (libraryToggle.hidden ? appMenuToggle : libraryToggle).focus();
        return;
      }
      if (!downloadsPanel.hidden) {
        setDownloadsOpen(false);
        return;
      }
      if (!promptPanel.hidden) {
        dismissActivePrompt();
        return;
      }
      if (!permissionPanel.hidden) {
        answerPrompt("dismiss");
        return;
      }
      if (activeRecord && hasVisibleLoading(activeRecord.session.state)) {
        activeRecord.session.stop();
        return;
      }
    }
    if (!(event.ctrlKey || event.metaKey)) {
      return;
    }
    const key = event.key.toLowerCase();
    if (key === "n" && event.shiftKey) {
      event.preventDefault();
      runtime.openPlatformWindow({
        ownerWindow: window,
        uri: document.documentURI,
        privateMode: true,
      });
    } else if (key === "b" && event.shiftKey) {
      event.preventDefault();
      const current = runtime.appearanceSettings.bookmarkBar;
      const next = current === "always" ? "newtab" : current === "newtab" ? "never" : "always";
      runtime.setAppearanceSetting("bookmarkBar", next);
    } else if (key === "h" && !event.shiftKey) {
      event.preventDefault();
      loadProductPage("navis://history/");
    } else if (key === "o" && event.shiftKey) {
      event.preventDefault();
      loadProductPage("navis://bookmarks/");
    } else if (key === "tab") {
      event.preventDefault();
      const orderedRecords = orderedSessionRecords();
      const currentIndex = orderedRecords.indexOf(activeRecord);
      const offset = event.shiftKey ? -1 : 1;
      const targetIndex =
        (currentIndex + offset + orderedRecords.length) % orderedRecords.length;
      selectSession(orderedRecords[targetIndex], { focusContent: true });
    } else if (key === "l") {
      event.preventDefault();
      address.focus();
      address.select();
    } else if (key === "t") {
      event.preventDefault();
      openNewTab();
    } else if (key === "w") {
      event.preventDefault();
      closeSession(activeRecord);
    } else if (key === "r") {
      event.preventDefault();
      activeRecord?.session.reload();
    } else if (/^[1-9]$/.test(key)) {
      const orderedRecords = orderedSessionRecords();
      const record =
        key === "9" ? orderedRecords.at(-1) : orderedRecords[Number(key) - 1];
      if (record) {
        event.preventDefault();
        selectSession(record);
      }
    }
  });

  const renderBrowserFullscreen = () => {
    const maximized = window.windowState === window.STATE_MAXIMIZED;
    const maximizeIcon = maximized ? "restore" : "maximize";
    windowMaximize.dataset.platformIcon = maximizeIcon;
    setIcon(windowMaximize, maximizeIcon);
    const maximizeLabel = t(
      maximized ? "chrome.restoreWindow" : "chrome.maximizeWindow",
    );
    windowMaximize.title = t(maximized ? "chrome.restore" : "chrome.maximize");
    windowMaximize.setAttribute("aria-label", maximizeLabel);
    const browserFullscreen =
      window.fullScreen && !activeRecord?.session.state.fullscreen;
    const wasBrowserFullscreen = document.documentElement.hasAttribute(
      "data-browser-fullscreen",
    );
    document.documentElement.toggleAttribute(
      "data-browser-fullscreen",
      browserFullscreen,
    );
    if (browserFullscreen && !wasBrowserFullscreen) {
      showFullscreenHint(t("app.name"), t("chrome.fullscreenF11"));
    } else if (
      !browserFullscreen &&
      wasBrowserFullscreen &&
      !activeRecord?.session.state.fullscreen
    ) {
      hideFullscreenHint();
    }
  };
  window.addEventListener("sizemodechange", renderBrowserFullscreen);
  renderBrowserFullscreen();

  renderDownloads();
  renderBookmarkBarVisibility();
  const appearanceDelegate = {
    onAppearanceChanged(state) {
      document.documentElement.dataset.appearance = state.theme;
      document.documentElement.style.setProperty("--navis-user-accent", state.accent);
      renderBookmarkBarVisibility();
      libraryToggle.hidden = !state.historyButton;
    },
  };
  runtime.addAppearanceDelegate(appearanceDelegate);
  appearanceDelegate.onAppearanceChanged(runtime.appearanceSettings);
  const bookmarkBarResizeObserver = new ResizeObserver(() => {
    window.requestAnimationFrame(layoutBookmarkBarOverflow);
  });
  bookmarkBarResizeObserver.observe(bookmarkBarItems);
  const commandLineUris = runtime.getStartupURIs({ window, fallbackURI: null });
  const restoredState = privateMode ? [] : await runtime.loadSessionState();
  let startupEntries;
  if (commandLineUris.length) {
    startupEntries = commandLineUris.map((url, index) => ({
      url,
      selected: index === 0,
    }));
  } else if (restoredState.length) {
    startupEntries = restoredState;
  } else {
    startupEntries = [{ url: DEFAULT_URI, selected: true }];
  }
  const startupRecords = startupEntries.map((entry) => ({
    entry,
    record: createSession(entry.url, { activate: false }),
  }));
  restoringSessions = false;
  selectSession(
    startupRecords.find(({ entry }) => entry.selected)?.record ??
      startupRecords[0].record,
    { focusContent: true },
  );
  persistSessionState({ immediate: true });
  window.addEventListener(
    "unload",
    () => {
      clearTimeout(sessionPersistenceTimer);
      clearTimeout(clearSiteDataTimer);
      clearTimeout(clearPasswordsTimer);
      clearTimeout(fullscreenHintTimer);
      clearTimeout(tabWheelResetTimer);
      if (downloadsRenderFrame) window.cancelAnimationFrame(downloadsRenderFrame);
      if (downloadsSeenFrame) window.cancelAnimationFrame(downloadsSeenFrame);
      downloadsVisibilityObserver.disconnect();
      window.removeEventListener("focus", scheduleDownloadsRender);
      document.removeEventListener("visibilitychange", scheduleDownloadsRender);
      if (extensionActionRenderFrame) {
        window.cancelAnimationFrame(extensionActionRenderFrame);
        extensionActionRenderFrame = 0;
      }
      bookmarkBarResizeObserver.disconnect();
      runtime.removeAppearanceDelegate(appearanceDelegate);
      if (!privateMode && records.size && activeRecord) {
        saveCurrentSessionState().catch(console.error);
      }
      runtime.close();
    },
    { once: true },
  );
}

const start = () => {
  initialize().catch((error) => {
    console.error("Navis initialization failed", error);
    document.title = t("chrome.startupFailedTitle");
    const statusNode = document.getElementById("loading-state");
    if (statusNode) {
      statusNode.textContent = t("chrome.startupFailed", {
        message: error.message,
      });
      statusNode.setAttribute("aria-label", statusNode.textContent);
      statusNode.dataset.visible = "true";
    }
  });
};

if (document.readyState === "loading") {
  window.addEventListener("DOMContentLoaded", start, { once: true });
} else {
  start();
}
