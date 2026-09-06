workspace "Graphite C4 Workspace" {
    model {
        g_system_subject = softwareSystem "Subject" "Derived from the Graphite code graph" {
            g_container_application_runtime = container "Checkout Runtime" "Inferred runtime container synthesized for component view" "JVM bytecode" {
                g_component_service_and_repository = component "Service and Repository" "Connects the Checkout Runtime capability to external collaborators and dependency boundaries" ""
                g_component_api = component "Api" "Accepts external requests and translates them into internal application operations" ""
            }
        }
        g_component_api -> g_component_service_and_repository "Api routes work to Service and Repository"
    }
    views {
        component g_container_application_runtime "graphite-component-container-application-runtime" {
            include *
            autolayout tb
        }
        theme default
    }
}