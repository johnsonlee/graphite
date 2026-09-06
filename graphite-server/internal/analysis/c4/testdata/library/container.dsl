workspace "Graphite C4 Workspace" {
    model {
        g_system_subject = softwareSystem "Subject" "Derived from the Graphite code graph"
        g_dependency_runtime_java = softwareSystem "Java Runtime" "External dependency inferred from code graph evidence"
    }
    views {
        container g_system_subject "graphite-container" {
            include *
            autolayout tb
        }
        theme default
    }
}