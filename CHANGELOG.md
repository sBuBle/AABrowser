# Changelog

## 3.5
- On car displays and phones in landscape, the browser menu fits on one screen: back, forward and reload sit in a slim header with Check for Updates, the shortcuts are compact, and the address bar uses the whole column.
- Settings use two columns on wide screens (car displays, landscape, tablets).
- Phones in landscape use the floating menu button like the car screen, instead of a bottom toolbar taking a sixth of the page.
- Deleting a bookmark can be undone.
- Settings: clearer names and descriptions, display scale presets no longer break onto two lines with large fonts, and dates follow the app's language.
- Fixed the back gesture (and back key) doing nothing after opening a page from the address bar or closing a sheet by swiping, and leaving the app instead of going back after the app had been in the background.
- The ad blocker now blocks YouTube video ads: it removes the ads from YouTube's player data, like uBlock Origin, and skips any ad that still starts. Sites where you allowed ads no longer get the built-in ad hiding either.
- Added in-app updates from GitHub releases: "Check for Updates" now offers "Update now", which downloads only the changes since the installed version (about 1 MB instead of about 10 MB), rebuilds and checks the new version on the phone and installs it. After you allow AABrowser to install apps once, later updates install without a prompt. Releases without update files still link to the release page.
- Security: backups and device-to-device transfer no longer copy the browser's cookies and site data, which could hand signed-in sessions to another device.
- Security: HTTPS pages can no longer load insecure scripts or frames over HTTP (images and media still load, like in Chrome), and web pages can no longer open local files, other apps' content or top-level `data:` URLs.
- Security: "Proceed anyway" on a certificate warning now trusts only that certificate, not any certificate for the site, and the address bar shows the page as not secure instead of a padlock.
- Security: the update check and site icon downloads now have timeouts and size limits, and the update check only opens GitHub release pages.
- Removed the notification permission request on launch; the app never shows notifications.
- Fixed "Allow once" on the insecure connection prompt asking again straight away.
- Sites' own error pages (404, 500, ...) are shown again instead of a "Can't reach this site" page, which also broke sites behind Cloudflare's bot check.
- The address bar shows the address that failed instead of an internal `file://` link on the "Can't reach this site" page.
- Tapping the address bar now selects the whole address, so typing replaces it.
- Fixed "Check for Updates" saying an update is available when the installed version is the same or newer.
- On phones set to Hebrew, Arabic or another right-to-left language, the (English) app is no longer mirrored, and punctuation no longer jumps to the start of sentences. Dialog buttons are no longer partly in the system language.
- Debug builds install next to the release app instead of replacing it.
- Removed the original project's usage analytics (an "app opened" event sent on every launch), its sponsor section on the start page and its AA Anything banner and settings; the analytics ID left on the phone is deleted. The GitHub links in the menu and in Settings now open this fork.

## 3.4
- The phone and the car screen now share the same tabs: pages opened, closed or switched on one show up on the other when you turn to it, and one screen no longer overwrites the tabs saved by the other.
- Added a bottom toolbar on the phone (back, forward, address, tabs, menu), like other mobile browsers; the car screen and full screen mode keep the floating button. It can be turned off under In-app controls.
- The browser menu now uses two columns on wide, short screens (car displays, phones in landscape), so it fits without scrolling, and sizes itself to the screen height.
- Sheets no longer stretch across very wide screens, and the menu footer buttons are larger touch targets.
- Tapping the address in the toolbar (or the floating button in address bar mode) now focuses the address field and opens the keyboard.
- Added "Block pop-ups and redirects" (on by default): pages can only open windows or send you to another site when you tap a link. Blocked ones show a message with an Allow button, and allowed sites can be managed in settings.
- Fixed "net::ERR_FAILED" messages popping up for blocked ads, and blocked ad frames showing an error page.
- Fixed the ad blocker letting blocked requests "succeed" with an empty response, so pages and ad block tests saw ads as not blocked; blocked requests now fail like in other ad blockers.
- Added the HaGeZi Light filter list, which covers phone and app telemetry servers the other lists miss.
- Added an ad blocker card to the browser menu showing how many ads were blocked on the page, with a switch to turn the ad blocker off for the current site.
- Added an "Ad blocking" section in settings with the list of sites where ads are allowed.

## 3.3
- Added new User Agent profiles (Firefox, Edge, Samsung Internet).
- Added a Custom User Agent profile with user-defined string.
