package com.examseating.exception;

/**
 * The caller is authenticated but may not do this.
 *
 * <p>Used where the reason matters and should reach the user — for example
 * an account that signs in at the customer sign-in although it is a platform
 * administrator, or a member who tries to administer an institution they do
 * not belong to. The message is shown to the caller as it is written.</p>
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
