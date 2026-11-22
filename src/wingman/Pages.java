package wingman;

/**
 * Wingman's pages on the campaign map (settings, officer links, friend refits, wish lists): one at a time, and none
 * left taking the keys or the mouse once it is no longer drawn. A page opens only if no other is open (two would
 * each take the keys and fight over Esc); and a page that is open but hasn't been drawn for a moment (a screen of
 * the game's own came up over it, or its drawing stopped) closes itself, as if Esc had been pressed.
 */
final class Pages {
    private Pages() {}

    /** How long an open page may go undrawn before it closes itself (ms). */
    static final long UNDRAWN_MS = 1500;

    /** Any of the pages is open now. */
    static boolean anyOpen() {
        return CampaignSettings.isOpen() || OfficerLinkPanel.isOpen() || FriendRefitPanel.isOpen() || WishListPanel.isOpen();
    }

    /** An open page last drawn (or opened) at 'seen' has gone undrawn too long. */
    static boolean stale(long seen) {
        return seen > 0 && System.currentTimeMillis() - seen > UNDRAWN_MS;
    }
}
