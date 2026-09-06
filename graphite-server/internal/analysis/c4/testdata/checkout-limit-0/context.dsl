workspace "Graphite C4 Workspace" {
    model {
        g_person_http_clients = person "HTTP Clients" "External clients invoking detected HTTP endpoints"
        g_system_application = softwareSystem "Checkout" "Executable software system inferred from the Graphite code graph"
        g_person_http_clients -> g_system_application "Invokes Checkout through its HTTP interface"
    }
    views {
        systemContext g_system_application "graphite-context" {
            include *
            autolayout tb
        }
        theme default
    }
}