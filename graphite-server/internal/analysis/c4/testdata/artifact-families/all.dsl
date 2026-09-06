workspace "Graphite C4 Workspace" {
    model {
        g_person_http_clients = person "HTTP Clients" "External clients invoking detected HTTP endpoints"
        g_system_application = softwareSystem "Checkout" "Executable software system inferred from the Graphite code graph" {
            g_container_application_runtime = container "Checkout Runtime" "Executable/deployable runtime container inferred from entrypoint, endpoint, and archive evidence" "JVM bytecode" {
                g_component_service_and_repository = component "Service and Repository" "Connects the Checkout Runtime capability to external collaborators and dependency boundaries" ""
                g_component_api = component "Api" "Accepts external requests and translates them into internal application operations" ""
            }
        }
        g_dependency_library_lucene = softwareSystem "lucene" "Reusable third-party library capabilities referenced from the subject system"
        g_dependency_library_commons = softwareSystem "commons" "Reusable third-party library capabilities referenced from the subject system"
        g_dependency_artifact_postgresql_42_7_3 = softwareSystem "postgresql-42.7.3" "Reusable third-party library capabilities referenced from the subject system"
        g_dependency_artifact_alpha_lib_1_0 = softwareSystem "alpha-lib-1.0" "Reusable third-party library capabilities referenced from the subject system"
        g_dependency_artifact_zeta_lib_1_0 = softwareSystem "zeta-lib-1.0" "Reusable third-party library capabilities referenced from the subject system"
        g_dependency_namespace_com_partner_payment = softwareSystem "com.partner.payment" "External software system candidate inferred from referenced-but-absent classes"
        g_dependency_runtime_java = softwareSystem "Java Runtime" "Language and platform runtime supporting the application and its libraries"
        g_dependency_artifact_lucene_analysis_9_12_0 = softwareSystem "lucene-analysis-9.12.0" "External dependency inferred from code graph evidence"
        g_dependency_artifact_lucene_core_9_12_0 = softwareSystem "lucene-core-9.12.0" "External dependency inferred from code graph evidence"
        g_dependency_artifact_commons_lang3_3_14_0 = softwareSystem "commons-lang3-3.14.0" "External dependency inferred from code graph evidence"
        g_dependency_artifact_commons_io_2_15_0 = softwareSystem "commons-io-2.15.0" "External dependency inferred from code graph evidence"
        g_person_http_clients -> g_system_application "Invokes Checkout through its HTTP interface"
        g_system_application -> g_dependency_library_lucene "Checkout uses lucene"
        g_system_application -> g_dependency_library_commons "Checkout uses commons"
        g_system_application -> g_dependency_artifact_postgresql_42_7_3 "Checkout uses postgresql-42.7.3"
        g_system_application -> g_dependency_artifact_alpha_lib_1_0 "Checkout uses alpha-lib-1.0"
        g_system_application -> g_dependency_artifact_zeta_lib_1_0 "Checkout uses zeta-lib-1.0"
        g_system_application -> g_dependency_namespace_com_partner_payment "Checkout uses com.partner.payment"
        g_container_application_runtime -> g_dependency_namespace_com_partner_payment "Checkout Runtime uses com.partner.payment"
        g_container_application_runtime -> g_dependency_artifact_lucene_analysis_9_12_0 "Checkout Runtime uses lucene-analysis-9.12.0"
        g_container_application_runtime -> g_dependency_artifact_lucene_core_9_12_0 "Checkout Runtime uses lucene-core-9.12.0"
        g_container_application_runtime -> g_dependency_artifact_postgresql_42_7_3 "Checkout Runtime uses postgresql-42.7.3"
        g_container_application_runtime -> g_dependency_runtime_java "Checkout Runtime runs on Java Runtime"
        g_container_application_runtime -> g_dependency_artifact_commons_lang3_3_14_0 "Checkout Runtime uses commons-lang3-3.14.0"
        g_container_application_runtime -> g_dependency_artifact_commons_io_2_15_0 "Checkout Runtime uses commons-io-2.15.0"
        g_container_application_runtime -> g_dependency_artifact_alpha_lib_1_0 "Checkout Runtime uses alpha-lib-1.0"
        g_container_application_runtime -> g_dependency_artifact_zeta_lib_1_0 "Checkout Runtime uses zeta-lib-1.0"
        g_component_api -> g_component_service_and_repository "Api routes work to Service and Repository"
        g_dependency_library_lucene -> g_dependency_library_commons "lucene builds on commons"
        g_dependency_library_commons -> g_dependency_artifact_postgresql_42_7_3 "commons builds on postgresql-42.7.3"
        g_dependency_artifact_postgresql_42_7_3 -> g_dependency_runtime_java "postgresql-42.7.3 runs on Java Runtime"
        g_dependency_artifact_alpha_lib_1_0 -> g_dependency_artifact_postgresql_42_7_3 "alpha-lib-1.0 builds on postgresql-42.7.3"
        g_dependency_artifact_zeta_lib_1_0 -> g_dependency_library_lucene "zeta-lib-1.0 builds on lucene"
        g_dependency_artifact_zeta_lib_1_0 -> g_dependency_artifact_postgresql_42_7_3 "zeta-lib-1.0 builds on postgresql-42.7.3"
        g_dependency_artifact_commons_lang3_3_14_0 -> g_dependency_runtime_java "commons-lang3-3.14.0 runs on Java Runtime"
    }
    views {
        systemContext g_system_application "graphite-context" {
            include *
            autolayout tb
        }
        container g_system_application "graphite-container" {
            include *
            autolayout tb
        }
        component g_container_application_runtime "graphite-component-container-application-runtime" {
            include *
            autolayout tb
        }
        theme default
    }
}