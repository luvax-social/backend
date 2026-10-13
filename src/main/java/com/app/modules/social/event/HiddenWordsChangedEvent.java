package com.app.modules.social.event;

/**
 * Published inside the transaction that changes the platform-wide hidden word list.
 *
 * <p>{@code HiddenWordService} rebuilds its in-memory copy of the list after that transaction
 * commits, so a rolled-back change never reaches the cache.
 */
public record HiddenWordsChangedEvent() {}
