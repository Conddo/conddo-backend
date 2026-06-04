package io.conddo.studio.common;

/**
 * §21.3 — a site must keep a home page. Deleting the last home page (or the
 * only page) is refused with 422 {@code HOME_PAGE_REQUIRED}; the FE should
 * prompt the user to promote another page first.
 */
public class HomePageRequiredException extends RuntimeException {
    public HomePageRequiredException() {
        super("A site must have a home page");
    }
}
