workspace "Graphite C4 Workspace" {
    model {
        g_system_subject = softwareSystem "Subject" "Derived from the Graphite code graph" {
            g_container_application_runtime = container "Checkout Runtime" "Inferred runtime container synthesized for component view" "JVM bytecode" {
                g_component_service_and_repository = component "Service and Repository" "Connects the Checkout Runtime capability to external collaborators and dependency boundaries" ""
                g_component_api = component "Api" "Accepts external requests and translates them into internal application operations" ""
                g_component_island00 = component "Island00" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island01 = component "Island01" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island02 = component "Island02" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island03 = component "Island03" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island04 = component "Island04" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island05 = component "Island05" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island06 = component "Island06" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island07 = component "Island07" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island08 = component "Island08" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island09 = component "Island09" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island10 = component "Island10" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island11 = component "Island11" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island12 = component "Island12" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island13 = component "Island13" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island14 = component "Island14" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island15 = component "Island15" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island16 = component "Island16" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island17 = component "Island17" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island18 = component "Island18" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island19 = component "Island19" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island20 = component "Island20" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island21 = component "Island21" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island22 = component "Island22" "Implements a structurally central part of the Checkout Runtime capability" ""
                g_component_island23 = component "Island23" "Implements a structurally central part of the Checkout Runtime capability" ""
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