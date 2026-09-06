workspace "Graphite C4 Workspace" {
    model {
        g_system_subject = softwareSystem "Subject" "Derived from the Graphite code graph" {
            g_container_application_runtime = container "Checkout Runtime" "Executable/deployable runtime container inferred from entrypoint, endpoint, and archive evidence" "JVM bytecode"
        }
        g_dependency_namespace_com_partner_payment = softwareSystem "com.partner.payment" "External dependency inferred from code graph evidence"
        g_dependency_artifact_postgresql_42_7_3 = softwareSystem "postgresql-42.7.3" "External dependency inferred from code graph evidence"
        g_dependency_runtime_java = softwareSystem "Java Runtime" "External dependency inferred from code graph evidence"
        g_container_application_runtime -> g_dependency_namespace_com_partner_payment "Checkout Runtime uses com.partner.payment"
        g_container_application_runtime -> g_dependency_artifact_postgresql_42_7_3 "Checkout Runtime uses postgresql-42.7.3"
        g_container_application_runtime -> g_dependency_runtime_java "Checkout Runtime runs on Java Runtime"
        g_dependency_artifact_postgresql_42_7_3 -> g_dependency_runtime_java "postgresql-42.7.3 runs on Java Runtime"
    }
    views {
        container g_system_subject "graphite-container" {
            include *
            autolayout tb
        }
        theme default
    }
}