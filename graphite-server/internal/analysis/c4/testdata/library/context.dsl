workspace "Graphite C4 Workspace" {
    model {
        g_person_host_applications = person "Host Applications" "Applications or services that embed and invoke the library"
        g_system_library = softwareSystem "Api Library" "A reusable library artifact inferred from the analyzed code graph"
        g_dependency_runtime_java = softwareSystem "Java Runtime" "Language and platform runtime supporting the application and its libraries"
        g_person_host_applications -> g_system_library "Uses Api Library from a host application context"
        g_system_library -> g_dependency_runtime_java "Api Library runs on Java Runtime"
    }
    views {
        systemContext g_system_library "graphite-context" {
            include *
            autolayout tb
        }
        theme default
    }
}