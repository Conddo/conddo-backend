package io.conddo.core.auth;

/**
 * The email is already registered on Conddo (either as a tenant
 * owner or as a staff invitee). Promoted to a top-level class so
 * both {@link RegistrationService} and {@link StaffInviteService}
 * can throw it and the global handler maps it to the same wire
 * code {@code EMAIL_ALREADY_REGISTERED} regardless of source.
 */
public class EmailAlreadyInUseException extends RuntimeException {

    public EmailAlreadyInUseException(String email) {
        super("A user with email " + email + " already exists");
    }
}
