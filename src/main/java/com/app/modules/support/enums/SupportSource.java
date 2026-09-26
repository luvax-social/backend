package com.app.modules.support.enums;

/**
 * How a ticket arrived, mirroring the {@code support_source} PostgreSQL enum.
 *
 * <p>{@code SIGNED_LINK} means a single-use token from a moderation mail authorised exactly this
 * one ticket against one audit row. It never minted a session.
 */
public enum SupportSource {
    AUTHENTICATED,
    SIGNED_LINK,
    PUBLIC_FORM;
}
