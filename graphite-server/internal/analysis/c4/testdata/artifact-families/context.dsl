workspace "Graphite C4 Workspace" {
    model {
        g_person_http_clients = person "HTTP Clients" "External clients invoking detected HTTP endpoints"
        g_system_application = softwareSystem "Checkout" "Executable software system inferred from the Graphite code graph"
        g_dependency_library_lucene = softwareSystem "lucene" "Reusable third-party library capabilities referenced from the subject system"
        g_dependency_library_commons = softwareSystem "commons" "Reusable third-party library capabilities referenced from the subject system"
        g_dependency_artifact_postgresql_42_7_3 = softwareSystem "postgresql-42.7.3" "Reusable third-party library capabilities referenced from the subject system"
        g_dependency_artifact_alpha_lib_1_0 = softwareSystem "alpha-lib-1.0" "Reusable third-party library capabilities referenced from the subject system"
        g_dependency_artifact_zeta_lib_1_0 = softwareSystem "zeta-lib-1.0" "Reusable third-party library capabilities referenced from the subject system"
        g_dependency_namespace_com_partner_payment = softwareSystem "com.partner.payment" "External software system candidate inferred from referenced-but-absent classes"
        g_dependency_runtime_java = softwareSystem "Java Runtime" "Language and platform runtime supporting the application and its libraries"
        g_person_http_clients -> g_system_application "Invokes Checkout through its HTTP interface"
        g_system_application -> g_dependency_library_lucene "Checkout uses lucene"
        g_system_application -> g_dependency_library_commons "Checkout uses commons"
        g_system_application -> g_dependency_artifact_postgresql_42_7_3 "Checkout uses postgresql-42.7.3"
        g_system_application -> g_dependency_artifact_alpha_lib_1_0 "Checkout uses alpha-lib-1.0"
        g_system_application -> g_dependency_artifact_zeta_lib_1_0 "Checkout uses zeta-lib-1.0"
        g_system_application -> g_dependency_namespace_com_partner_payment "Checkout uses com.partner.payment"
        g_dependency_library_lucene -> g_dependency_library_commons "lucene builds on commons"
        g_dependency_library_commons -> g_dependency_artifact_postgresql_42_7_3 "commons builds on postgresql-42.7.3"
        g_dependency_artifact_postgresql_42_7_3 -> g_dependency_runtime_java "postgresql-42.7.3 runs on Java Runtime"
        g_dependency_artifact_alpha_lib_1_0 -> g_dependency_artifact_postgresql_42_7_3 "alpha-lib-1.0 builds on postgresql-42.7.3"
        g_dependency_artifact_zeta_lib_1_0 -> g_dependency_library_lucene "zeta-lib-1.0 builds on lucene"
        g_dependency_artifact_zeta_lib_1_0 -> g_dependency_artifact_postgresql_42_7_3 "zeta-lib-1.0 builds on postgresql-42.7.3"
    }
    views {
        systemContext g_system_application "graphite-context" {
            include *
            autolayout tb
        }
        theme default
    }
}