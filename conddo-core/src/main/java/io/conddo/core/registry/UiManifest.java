package io.conddo.core.registry;

import java.util.List;

/**
 * The UI a capability tool contributes to the frontend (Architecture §7.2/§16):
 * a sidebar nav entry, its routes, and any dashboard widgets. The React app
 * builds nav/routes from these per the tenant's active tools — it hardcodes
 * nothing. Thin v1: navItem + routes; widgets/permissions/config come later.
 */
public record UiManifest(String toolId, NavItem navItem, List<Route> routes, List<Widget> widgets) {

    /** A sidebar nav entry; {@code icon} is a lucide-react icon name; {@code order} sorts the sidebar. */
    public record NavItem(String label, String icon, String path, int order) {
    }

    /** A frontend route the tool owns. */
    public record Route(String path, String component) {
    }

    /** A dashboard widget the tool contributes to a zone (metric/chart/list/sidebar/alert). */
    public record Widget(String component, String zone) {
    }
}
