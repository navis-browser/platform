/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

export const PROFILE_MESSAGES = Object.freeze({
  "en-US": Object.freeze({
    title: "Profiles", description: "Keep your browsing data, settings and user information separate for each profile.",
    user: "Your profile", edit: "Edit your profile", create: "Add profile", manage: "Manage profiles",
    userName: "Name", accentColor: "Theme color", save: "Save", cancel: "Cancel", createAndOpen: "Create and open",
    default: "Default profile", current: "Current profile", inUse: "In use", setDefault: "Make default",
    launch: "Launch profile in a new window", remove: "Remove profile", removeTitle: "Remove this profile?",
    removeDetails: "This removes the profile from Navis. Keep its files to preserve bookmarks, passwords and other browsing data, or explicitly delete them.",
    keepFiles: "Remove, keeping files", deleteFiles: "Remove and delete files", restartSafe: "Restart with Add-ons Disabled",
    restart: "Restart normally", restartDetails: "Restart applies to every window using this profile.",
    loading: "Loading profiles…", saved: "Profile updated", failed: "The profile operation could not be completed.",
    createdLaunchFailed: "Profile created, but its window could not be opened. Try again, or open it from Manage profiles.",
    retryLaunch: "Try opening again",
    outdated: "Another Navis process changed the profile list. Restart Navis before changing profiles.",
    unnamed: "Navis user", local: "Local profile on this device", close: "Close",
    createDescription: "Choose a name and color. The new profile will open in its own window with separate browsing data.",
    editDescription: "Personalize this profile on your device.", customColor: "Custom color",
    colorBlue: "Blue", colorTeal: "Teal", colorGreen: "Green", colorOrange: "Orange", colorPurple: "Purple", colorGray: "Gray",
  }),
  "zh-CN": Object.freeze({
    title: "个人资料", description: "每个个人资料分别保存浏览数据、设置和用户信息。",
    user: "您的个人资料", edit: "修改个人资料", create: "新建个人资料", manage: "管理个人资料",
    userName: "用户名", accentColor: "主题色", save: "保存", cancel: "取消", createAndOpen: "创建并打开",
    default: "默认个人资料", current: "当前个人资料", inUse: "使用中", setDefault: "设为默认",
    launch: "在新窗口中启动此个人资料", remove: "移除个人资料", removeTitle: "移除此个人资料？",
    removeDetails: "此操作将从 Navis 中移除个人资料。您可以保留文件以保留书签、密码及其他浏览数据，也可以明确选择删除文件。",
    keepFiles: "移除并保留文件", deleteFiles: "移除并删除文件", restartSafe: "禁用附加组件后重新启动",
    restart: "正常重新启动", restartDetails: "重新启动会影响使用此个人资料的所有窗口。",
    loading: "正在加载个人资料…", saved: "个人资料已更新", failed: "无法完成个人资料操作。",
    createdLaunchFailed: "个人资料已创建，但无法打开窗口。请重试，或稍后从“管理个人资料”中打开。",
    retryLaunch: "重新打开",
    outdated: "另一个 Navis 进程已更改个人资料列表。请重新启动 Navis 后再修改个人资料。",
    unnamed: "Navis 用户", local: "保存在此设备的本地个人资料", close: "关闭",
    createDescription: "选择用户名和颜色。新个人资料将在独立窗口中打开，浏览数据互不混用。",
    editDescription: "自定义此设备上的个人资料。", customColor: "自定义颜色",
    colorBlue: "蓝色", colorTeal: "青色", colorGreen: "绿色", colorOrange: "橙色", colorPurple: "紫色", colorGray: "灰色",
  }),
});

export function profileMessages(locale) {
  return PROFILE_MESSAGES[String(locale).toLowerCase().startsWith("zh") ? "zh-CN" : "en-US"];
}
