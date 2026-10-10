"""Exact reviewed JVM source rules for the fixed 26 raw-derived requests.

This module checks applicability against a separately replayed derivation plan.
It does not replay graphs or certify an owned producer by trusting a PASS field.
The caller must combine it with successful actual derivation/phase replay before
using these universes as HTTP correctness authority.
"""
import hashlib
import json
from pathlib import Path
import re

import audit_native_pressure_artifacts as artifacts
import jvm_pressure_oracles as model
import multigraph_pressure as common

require = common.require
MODULES = ('core', 'cypher', 'webgraph', 'explore', 'query', 'sootup')
BUILD = ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat')
# Generated from the two reviewed Git revisions, not observed query responses.
# Each tuple is (accepted4f, declared5a); None requires that file to be absent.
SOURCE_BYTES = {
    'build.gradle.kts': ('ef80ef8b48e8b24e0c876c74b7aa788cf119da11bc12f687689a54b4e183d4a1', 'ef80ef8b48e8b24e0c876c74b7aa788cf119da11bc12f687689a54b4e183d4a1'),
    'frontend/jvm/core/build.gradle.kts': ('d786a4a7bdc76917ce4be85f3903d8bd13a41bd2f16b726fe50781ccc1fe7e44', 'd786a4a7bdc76917ce4be85f3903d8bd13a41bd2f16b726fe50781ccc1fe7e44'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/Graphite.kt': ('79408f33d11f24a436b8cae0b702a983ecdccffa16a19cfe9878a004d95e3456', '79408f33d11f24a436b8cae0b702a983ecdccffa16a19cfe9878a004d95e3456'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/analysis/BranchReachabilityAnalysis.kt': ('d430cf31a4352eb6788e7b92d6b3118bfafa9af8be76514a05a4e83a0dc19cff', 'd430cf31a4352eb6788e7b92d6b3118bfafa9af8be76514a05a4e83a0dc19cff'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/analysis/DataFlowAnalysis.kt': ('3c21ba39930f774201bba65d23bd2e60970356d943091dae10d58326a6fd6126', '3c21ba39930f774201bba65d23bd2e60970356d943091dae10d58326a6fd6126'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/analysis/TypeHierarchyAnalysis.kt': ('bacb28a88905f8295dff7e44d1ed693438106197f4b09a8ff4399795055c2262', 'bacb28a88905f8295dff7e44d1ed693438106197f4b09a8ff4399795055c2262'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/CancellationCheckpoints.kt': ('5428f32e26e8eec312df9f6700bdfe5ab848c192d4450ad077660983badbc296', '5428f32e26e8eec312df9f6700bdfe5ab848c192d4450ad077660983badbc296'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/Edge.kt': ('7f830e9e787bc3b221282a1ee7fce5b4b6bca0855a872e9deb9a610eb55fde59', '7f830e9e787bc3b221282a1ee7fce5b4b6bca0855a872e9deb9a610eb55fde59'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/Node.kt': ('0fa8f876e567e0d359d9c8dd4bd7c1fafa3b3005cdb4b26cdd3ecd717bf226ea', '0fa8f876e567e0d359d9c8dd4bd7c1fafa3b3005cdb4b26cdd3ecd717bf226ea'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/TypeStructure.kt': ('ac829ac5e454f7ab82a4f90af5fd11be9a30f374b4138829c79d3435231d3750', 'ac829ac5e454f7ab82a4f90af5fd11be9a30f374b4138829c79d3435231d3750'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/DeclaredTypeExpansionBudget.kt': (None, 'f4d4192f5c0bf4c81f0e73c2d7927fc8dfba6b16a153dbf4615704549b59ca14'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/DeclaredTypeReferences.kt': (None, '26349a777c5c9affd6cef63d28c7c08e4d41366f3a7e73f4841d578a4098acec'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/DeclaredTypeTable.kt': (None, '42a72cbca5ec5768efec8fbba87d284a44fc15d80f30f79e4661bf2cb20ab435'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/DeclaredTypeValidationAccess.kt': (None, '9fd6e84a46c5fa9b57452c66ddcff85dc37bd41b2b154e60f30dd6ee4d4ba85b'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/DefaultGraph.kt': ('eee00ef8f0cfccc67f1e161c7f206ff612ef8bfe77b6c459ea56e43aa47cdb3f', 'c92beaf864d87c742e4942eefd6dff7783c24b330e91878dc6c3dd18445173c7'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/FullGraphBuilder.kt': ('1923e301f9300275224eef5cbe9f4fff0ab74f7d8a47a9d966cbb0d231cb9d51', '0b81a40fe1b08912cd070b5124d6e3681f62e53dfa2279c196491d52a5350faa'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/Graph.kt': ('6495cb34fd735e3660f652bfd3fd9bd190939895c25e3554a1e8b9557cb5e826', '36c1afcfdfe1b2ddd2ca8622c1483c0d6daa6b2efd38012aaee6861d683b2129'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/ImmutableDeclaredTypeStorage.kt': (None, '7331e772fdea59449d75a7514944292756d1a111ad1232ae9c57fe4f9a84c3da'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/MmapGraph.kt': ('50a76ffb6030f47b7e1a8948ce7c385ac6c67c366dae76511eff4bd33e47c242', 'fa9df1c45e2fe1b2c427284744f4fd718c4e3cc366611e7a9feab0ca49e38047'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/MmapGraphBuilder.kt': ('f37192aa297e7920b39510c1034c4a7b64de64cec87be4d49a20525e7d0ba3a7', '573fb7450b6e4d95fe1dc70ca0c765a6f00db80c7f6fbcaf9fc36919ff42623f'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/NodeIdCandidateLookup.kt': ('a61d84d6e9dee2de7fef7959a7b831357c0699f693bace11d261b0f102a8c023', 'a61d84d6e9dee2de7fef7959a7b831357c0699f693bace11d261b0f102a8c023'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/PackedBranchMetadataSnapshots.kt': ('fbec2cc0fcc6465db6b35812df60f0f797bd4c1e43bb6c81739610f84afc0671', 'fbec2cc0fcc6465db6b35812df60f0f797bd4c1e43bb6c81739610f84afc0671'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/PackedBranchMetadataSource.kt': ('f57766e3fd8b974c3b44944191c4fd4a3635cd51b48bcf3f3c9fa7a7d5162f42', 'f57766e3fd8b974c3b44944191c4fd4a3635cd51b48bcf3f3c9fa7a7d5162f42'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/PropertyTextCandidates.kt': ('550408ac5c626f3a9319f8842dcc0e874157852a6ddbaedb3bd7d21fe0c54b6f', '98df72acaf5c661cf34e2a99316ad9ba6bfa4ff6c4263d58c75f2f5102f9514f'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/input/ConstantFold.kt': ('9a846d40adf05d0a9b8b8a7146696156213b8559d72114eb23cade8de8dd86a4', '9a846d40adf05d0a9b8b8a7146696156213b8559d72114eb23cade8de8dd86a4'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/input/JavaArchiveLayout.kt': ('666317d7655427505dc53aafaf3fcd2ca17938d549ac0f6d7732c9ed1fe17756', '666317d7655427505dc53aafaf3fcd2ca17938d549ac0f6d7732c9ed1fe17756'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/input/ProjectLoader.kt': ('0e855a85f75031ff61cfa28d92d5a1ee10c6d4c8ecedc37efa8f9e77f7a4f843', '0e855a85f75031ff61cfa28d92d5a1ee10c6d4c8ecedc37efa8f9e77f7a4f843'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/input/ResourceAccessor.kt': ('5cdbad3c77836f8d1fc6e687ebbe08b1c5f1925330b6599ba2844a3adf5b34d8', '5cdbad3c77836f8d1fc6e687ebbe08b1c5f1925330b6599ba2844a3adf5b34d8'),
    'frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/query/QueryDsl.kt': ('06f750549013cfe3af3b48a5a3f5ba2b062087fd8b3fc28300444c887545aa45', '06f750549013cfe3af3b48a5a3f5ba2b062087fd8b3fc28300444c887545aa45'),
    'frontend/jvm/cypher/build.gradle.kts': ('e4bf24a5bebc1dbf8dc302c5dcabb820684af66bf3b3f9f250d905f638712213', '5b209f43de9dc8071d03f1b941196ee89a00a7410d252a2b261cf85d7c3dc8ba'),
    'frontend/jvm/cypher/src/main/antlr/CypherLexer.g4': ('71aa05d51600712b6798d4fe01865f2490562e2fe60c936ede451532a45d3180', '71aa05d51600712b6798d4fe01865f2490562e2fe60c936ede451532a45d3180'),
    'frontend/jvm/cypher/src/main/antlr/CypherParser.g4': ('b4bf4e008e816c00e4d162da6165f10957cdb3ede518f0f54fd18142bfe14364', 'b4bf4e008e816c00e4d162da6165f10957cdb3ede518f0f54fd18142bfe14364'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/CrossGraphValues.kt': ('090eb113da33baa4083e77b8a45847ebd33211cfbf5a6c992ca1cb7c083dc5ba', '3de3f3cc5bab90c68317efbc23d62eab18791924588f7b1a5c141c198f809b0d'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/CypherClause.kt': ('5917560b18527b23750f40ee9e50b970f3b01091e9f9f5de78bb5b11a1ce31ea', '5917560b18527b23750f40ee9e50b970f3b01091e9f9f5de78bb5b11a1ce31ea'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/CypherDslAdapter.kt': ('bd85057a259faf866a7d2a2f2a3096c28ecc8f8b1273559096bd9fe13de35f24', 'bd85057a259faf866a7d2a2f2a3096c28ecc8f8b1273559096bd9fe13de35f24'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/CypherExecutionBudget.kt': ('3fdfd1588e92c63a682bfcdbd3e9b79c7b67142a36d3b04700f49c4397ad18a3', '3fdfd1588e92c63a682bfcdbd3e9b79c7b67142a36d3b04700f49c4397ad18a3'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/CypherExecutor.kt': ('67380c0c83016e4344e3a894f30d6a1b38e60c42b4867d3c45374a3ac2305ebf', 'eb70b0e7ff10a5cb0d37b7eafb38a2d50faee3b2e912ceaebfa47838ef647d62'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/CypherFunctions.kt': ('593983531bb58b49099fa9cd0e769eaaa774c900fd752a2db12fbc84d7dd46fd', '16ec28d5509c6bfe707c384ceeb5f899aded1553a386dce164c064f4f7cd5c86'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/CypherPattern.kt': ('8f86fd2d98b5f488a0cbc9c231db2a6164149cbdd0d5359bcae26563b526b6e4', '8f86fd2d98b5f488a0cbc9c231db2a6164149cbdd0d5359bcae26563b526b6e4'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/CypherResult.kt': ('b1d8fbd97676f0959df98c0b9082f9939dfe014fb7edcd7f083c0ea6eb0c1212', 'b1d8fbd97676f0959df98c0b9082f9939dfe014fb7edcd7f083c0ea6eb0c1212'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/CypherValueSemantics.kt': ('f9556c9889531f01e95f21e02534538282e90e48042ff723b74eebcda7202ac3', 'f9556c9889531f01e95f21e02534538282e90e48042ff723b74eebcda7202ac3'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/DeclaredPropertyPresence.kt': (None, '7195d9a19cb28677dcf0b1f6998d84e1c720760010a6756bf721a404d37fc1f7'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/DeclaredTypeProperties.kt': (None, 'ee9686368435d0c295e87855e67f037b8f27fc7ef23045a1d3c478bf6d98959b'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/DirectProjectionResultCache.kt': ('91e1d2e5fd4d3577d4411ea2ac2fb819d59d8ff12fd9fcb0c7122f1d9906a78d', '91e1d2e5fd4d3577d4411ea2ac2fb819d59d8ff12fd9fcb0c7122f1d9906a78d'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/DynamicPropertyContains.kt': ('4b8c3dc4ec72e22f1fec219db2be14c9ef1785a9c46156d424669c71f1c472d1', '80af6cf9391220bbaf7ba768d2dd0dc85efc699ff0ba8021838bb003f1c0ab5b'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/ExpressionEvaluator.kt': ('600b211e287a6e451cd24a9e9089b8bbf0133ebf1afccef6454a4c4ca7bffbc2', '840e3ba30e8f29a1308edc3cadd3da45068a3a0e854057fc9944eeb3152ff417'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/GraphCypherExtensions.kt': ('5b8b0d60d582ca2b546c2de22934075740957fe3e61481808a51328b88dd1017', '5b8b0d60d582ca2b546c2de22934075740957fe3e61481808a51328b88dd1017'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/ImmutableCypherAst.kt': ('aae5c593f3ec0602857e4ece33dda9dac2ef1be9238e35313ec20f1458b73a92', 'aae5c593f3ec0602857e4ece33dda9dac2ef1be9238e35313ec20f1458b73a92'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/MethodQueryExecutor.kt': ('5d435e534f56a56c0d64ba1457d44775dc50a3851f8f68492e6bc22ed354a968', '322cb4cb22d24bc5ad54e4569bbd83f92eb129b0d7888b7632c82799a6d04ea2'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/NodePropertyAccessor.kt': ('9fb364635c1b9de8cca90976ebb8eb2cbb3bc73a10848862d6fc333f61400526', '07eea830aeac92a5192a08852e89d66b4e4b1f9b383581ba0c852d5665a86fb4'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/ParsedQuery.kt': ('bf1bd34aa0b65e638665789a1138325fbd9d6c83e54b113952ea35ae15e252e8', 'bf1bd34aa0b65e638665789a1138325fbd9d6c83e54b113952ea35ae15e252e8'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/PathFinder.kt': ('d3a3a0e472d441b2e294580590018144594851738265fdedfea5d2b539874bdf', 'd3a3a0e472d441b2e294580590018144594851738265fdedfea5d2b539874bdf'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/QueryPipeline.kt': ('c6d2f3939934cfcad3d26c7ba5fa31a644d212d6d86364dadbb587d8d3de2fb7', '23260af80b95b89b4650082812fd2dd30416e48c61072ca23febc21f547cb5ff'),
    'frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/SourcePredicatePushdown.kt': ('c27f36cac67c1a7dfd0c9591ec5f62c303bdf6b004942d1dfb2e20ca4021594f', 'c27f36cac67c1a7dfd0c9591ec5f62c303bdf6b004942d1dfb2e20ca4021594f'),
    'frontend/jvm/explore/build.gradle.kts': ('527d69b27741337625769f5cef321020158bb910bfba2ec739db3ec96db23de0', 'ca28ca6fe058718620f04ab94fc1061b83b7a60dfdb8ba1cae19f41f46f43b62'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/CypherClientCancellation.kt': ('769bbeab66a2296e79cff3496e89cef274b91c28ad9d40d1d4ad2871a3644725', '769bbeab66a2296e79cff3496e89cef274b91c28ad9d40d1d4ad2871a3644725'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/CypherQueryGuard.kt': ('8741d452aff5187a010d8fd020c4bcb65f0296f6cc740eb212623dfa70bf20e6', '8741d452aff5187a010d8fd020c4bcb65f0296f6cc740eb212623dfa70bf20e6'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/CypherResponseSerializer.kt': ('7583797a23d21580fccb051a8c0f595bb07e8056bf8920ed881d2e85796fe6d2', '7583797a23d21580fccb051a8c0f595bb07e8056bf8920ed881d2e85796fe6d2'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/EndpointExtractor.kt': ('585593900d3bef81f382fae70e07892f120ddd9bb58fc655d70808d29ce78eec', '7edc9a85fca57030f3cb5404feb0be5e255f7e38e95732c73c0294053418fc34'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/ExploreApiFields.kt': ('2ea121d404923294f4bbf2eba8f50642d97fb09b7665406fcdbc470393d0328b', '2ea121d404923294f4bbf2eba8f50642d97fb09b7665406fcdbc470393d0328b'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/ExploreCommand.kt': ('b2aa69d3d0f7cca4db158fee9bacea27b7a3c939e0fb36ecf32e892ed896c054', 'b2aa69d3d0f7cca4db158fee9bacea27b7a3c939e0fb36ecf32e892ed896c054'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/ExploreMain.kt': ('3dfd8270b8185899a6230df40fb28142cb77fd2af56f23c910224f5982078396', '3dfd8270b8185899a6230df40fb28142cb77fd2af56f23c910224f5982078396'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/ExploreRoutes.kt': ('040bc6df1979f43d03d694341eb56f90347c43fb7ea24d5530e0a5225fc00953', '017dc2d13cd957300815c29f804f6156f7a720e25a20c472741e305d076930db'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/GraphRegistry.kt': ('c01cbb8a4a143ad64c8b472f3b9392cda158e320ca75fe850d4430fc7739908d', 'c01cbb8a4a143ad64c8b472f3b9392cda158e320ca75fe850d4430fc7739908d'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/GraphiteVersionProvider.kt': ('7f522d2beda441d1ec140521b316f7a6df0ae479ba2787e0049c41f66808d99c', '7f522d2beda441d1ec140521b316f7a6df0ae479ba2787e0049c41f66808d99c'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/Helpers.kt': ('43753a6f94daa444914e5fcd595f590b33ad9bb75a27fb3f9f8f82becb9e0edd', '13c9a34f8f303f6712bf5af899a58bd4b7a131cdcfa7eb7dfc23ac5021f14cef'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/OpenApiSpecBuilder.kt': ('ea8113415352a73fd8ee3802456b181cf74da72b7d590c0560eeae80a32944a7', 'ea8113415352a73fd8ee3802456b181cf74da72b7d590c0560eeae80a32944a7'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/ServerPerformanceMetrics.kt': ('62502dd02c626bb4e9c924934d00871b45e93b775fad4e3182a93d63453432f3', '62502dd02c626bb4e9c924934d00871b45e93b775fad4e3182a93d63453432f3'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/TopologyGraph.kt': ('bc843b91d6102b7a250b156104f9f291547e31f0da27e4fd890005f39c460209', 'bc843b91d6102b7a250b156104f9f291547e31f0da27e4fd890005f39c460209'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/TopologyStore.kt': ('9f3896e4a9da5c0b557f244d1b5ecf0ba26c2af99c81a4c854cca67178f56745', '9f3896e4a9da5c0b557f244d1b5ecf0ba26c2af99c81a4c854cca67178f56745'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4ArchitectureService.kt': ('3ac9b1f818d63ae73f2f1955778de4f4ab4735ae0db616e5acfc348f565ee188', '3ac9b1f818d63ae73f2f1955778de4f4ab4735ae0db616e5acfc348f565ee188'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4InferenceConstants.kt': ('5dca9805ebaa30110585643c07715ee9c117c7740e12e3fd688339d420d81ff9', '5dca9805ebaa30110585643c07715ee9c117c7740e12e3fd688339d420d81ff9'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4MermaidRenderer.kt': ('114861ef4de7750df709f3e8ca403b453a078d5ec3d6f4efe7b356f36357b414', '114861ef4de7750df709f3e8ca403b453a078d5ec3d6f4efe7b356f36357b414'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4Metadata.kt': ('d04e430911bc297294b4bd2cea8acb87b275eb9f625bcc6096782846e6a95f64', 'd04e430911bc297294b4bd2cea8acb87b275eb9f625bcc6096782846e6a95f64'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4ModelConstants.kt': ('ad32db11c2cd2344e2f4425c803a5c1937e95e10a9533ce163de9263a818b576', 'ad32db11c2cd2344e2f4425c803a5c1937e95e10a9533ce163de9263a818b576'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4ModelInferer.kt': ('cbbd5c2af4236a7f4e9430d510ee8ddcfc67565b573d2df229f8568e4eecef7e', 'cbbd5c2af4236a7f4e9430d510ee8ddcfc67565b573d2df229f8568e4eecef7e'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4PlantUmlRenderer.kt': ('4bf75eb95de962ba361fc901f151ba0e036a400cdaf8d4db3180a8be2b8f57bd', '4bf75eb95de962ba361fc901f151ba0e036a400cdaf8d4db3180a8be2b8f57bd'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4RenderingPlan.kt': ('4a3d79ce91f4697ab708eeb0b45f7a1ab78c6d39bda421dd8f6736659fa4e3c0', '4a3d79ce91f4697ab708eeb0b45f7a1ab78c6d39bda421dd8f6736659fa4e3c0'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4StructurizrDslRenderer.kt': ('142cf3f0b896f84466b945df1281b04739722e2026231e77aae67bf0f4526cfd', '142cf3f0b896f84466b945df1281b04739722e2026231e77aae67bf0f4526cfd'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4StructurizrMapper.kt': ('2fdb5d7011cc082932b8c026bda8adceafcd9cac9d19c3a6deadd65b98de2d66', '2fdb5d7011cc082932b8c026bda8adceafcd9cac9d19c3a6deadd65b98de2d66'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4StructurizrModel.kt': ('9b926bf1ea9a8f5c2b8eff5aef3f2831b6359c74a036083205388e8ab20a34f6', '9b926bf1ea9a8f5c2b8eff5aef3f2831b6359c74a036083205388e8ab20a34f6'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4Support.kt': ('32ccb4b388aed65c60bf9940191aa666a5624faf45ee8b8064507a9e0e6f9022', '32ccb4b388aed65c60bf9940191aa666a5624faf45ee8b8064507a9e0e6f9022'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4TextDiagramRenderer.kt': ('7d8a966f81af9ee0839c398d82f66ebce46c291001749fcf223d8df3431e3b6b', '7d8a966f81af9ee0839c398d82f66ebce46c291001749fcf223d8df3431e3b6b'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4Types.kt': ('e2b3cbdda2ac014a7d6fde07687585705d5c4687bde9547e9683d2a177c6f57f', 'e2b3cbdda2ac014a7d6fde07687585705d5c4687bde9547e9683d2a177c6f57f'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/C4WireCodec.kt': ('6f2db7cbb89fa49b36b9d976cc53b9f0db3e49d20b304ad5ac5599150057a89f', '6f2db7cbb89fa49b36b9d976cc53b9f0db3e49d20b304ad5ac5599150057a89f'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/ComponentSelector.kt': ('041cbe3781c7292a97ceff18fd41cc8f98a824f7814d07d998bd4689ea2006c2', '041cbe3781c7292a97ceff18fd41cc8f98a824f7814d07d998bd4689ea2006c2'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/ContainerClusterer.kt': ('8d88704008756523eda0d81556dcf00d3e8b3cd8cf1fc5ecce66cf4b7ed513c4', '8d88704008756523eda0d81556dcf00d3e8b3cd8cf1fc5ecce66cf4b7ed513c4'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/ExternalSystemClassifier.kt': ('8def9b1e26778d3f48f5dd4294605e2099c54151b8cdc90d0125170cbdf2236d', '8def9b1e26778d3f48f5dd4294605e2099c54151b8cdc90d0125170cbdf2236d'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/SubjectDetector.kt': ('50f99ed4fbf15b7d47c336b5304f092942e2c847841bf6f1b8a251c40580ef71', '50f99ed4fbf15b7d47c336b5304f092942e2c847841bf6f1b8a251c40580ef71'),
    'frontend/jvm/explore/src/main/kotlin/io/johnsonlee/graphite/cli/c4/SystemBoundaryDetector.kt': ('02ae98a8ac9c95b0fee202b844327f7a7fbc620d0bccf587cdfeff1e1ff5ec83', '02ae98a8ac9c95b0fee202b844327f7a7fbc620d0bccf587cdfeff1e1ff5ec83'),
    'frontend/jvm/explore/src/main/resources/web/app.js': ('545964ac19fbe0e7ae5f02fedf20005d832a7270277e69c7f27015a3fb6d2889', '545964ac19fbe0e7ae5f02fedf20005d832a7270277e69c7f27015a3fb6d2889'),
    'frontend/jvm/explore/src/main/resources/web/index.html': ('cde253d3a96ba6fa9562bacdd43373fc1b6a73f73feddacb637a8a2421d61057', 'cde253d3a96ba6fa9562bacdd43373fc1b6a73f73feddacb637a8a2421d61057'),
    'frontend/jvm/explore/src/main/resources/web/style.css': ('fc3c32e70df3f3fe3864eea6b431a36a8d837adbe505f715fbc8f9aaf452abcd', 'fc3c32e70df3f3fe3864eea6b431a36a8d837adbe505f715fbc8f9aaf452abcd'),
    'frontend/jvm/explore/src/main/resources/web/ui-state.js': ('9f84675e7e61e7fe47d13a21e7a8c34bec7e866ff050f6d32ce8f6f9a5b1781c', '9f84675e7e61e7fe47d13a21e7a8c34bec7e866ff050f6d32ce8f6f9a5b1781c'),
    'frontend/jvm/query/build.gradle.kts': ('3c017a52fea7d268b9b7e249d0154880160f566ea21b8e7be407d71d8aed250e', '3c017a52fea7d268b9b7e249d0154880160f566ea21b8e7be407d71d8aed250e'),
    'frontend/jvm/query/src/main/kotlin/io/johnsonlee/graphite/cli/BuildCommand.kt': ('adeee2cfc81d49842ae612a2f4f41710847ddce780560fbac9969ef4f03dcea5', 'adeee2cfc81d49842ae612a2f4f41710847ddce780560fbac9969ef4f03dcea5'),
    'frontend/jvm/query/src/main/kotlin/io/johnsonlee/graphite/cli/FoldCommand.kt': ('779dfb97026a1dda2c24398ed288f564f97ba115724265d40267aa2b646c56fc', '779dfb97026a1dda2c24398ed288f564f97ba115724265d40267aa2b646c56fc'),
    'frontend/jvm/query/src/main/kotlin/io/johnsonlee/graphite/cli/FoldConfig.kt': ('bcad6ca751cdd7732dad607b8ca8f75df54ffc212aec2a8a2c3197d3065663c1', 'bcad6ca751cdd7732dad607b8ca8f75df54ffc212aec2a8a2c3197d3065663c1'),
    'frontend/jvm/query/src/main/kotlin/io/johnsonlee/graphite/cli/GraphiteCommand.kt': ('64cf34b569e8d040fb1b1a59255079ea55f0310543353e5eace2358c926c1c27', '64cf34b569e8d040fb1b1a59255079ea55f0310543353e5eace2358c926c1c27'),
    'frontend/jvm/query/src/main/kotlin/io/johnsonlee/graphite/cli/InputDigest.kt': ('e1f31e3f2c0b7402178f16bbc07d18c96129ce94362230cda47c904df3955c31', 'e1f31e3f2c0b7402178f16bbc07d18c96129ce94362230cda47c904df3955c31'),
    'frontend/jvm/query/src/main/kotlin/io/johnsonlee/graphite/cli/Main.kt': ('38ae84bf9e9c55d29f3fdf4e0ea67da938d8cbd7bc7a083e2c61678504eabecb', '38ae84bf9e9c55d29f3fdf4e0ea67da938d8cbd7bc7a083e2c61678504eabecb'),
    'frontend/jvm/query/src/main/kotlin/io/johnsonlee/graphite/cli/QueryCommand.kt': ('0360731709945045c47ccc5208001294bb38a9e89b39040627aa729c31fc2cbf', '0360731709945045c47ccc5208001294bb38a9e89b39040627aa729c31fc2cbf'),
    'frontend/jvm/sootup/build.gradle.kts': ('13dd3daa81800c7db73b89555173b504595a2b2d8da1784f919a3dc68de7ce86', '13dd3daa81800c7db73b89555173b504595a2b2d8da1784f919a3dc68de7ce86'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/ArchiveResourceAccessor.kt': ('2353fe49ff4ec9e08a5f9859e82061df4093dd817d1f6c7bade64f1df7ab3c1f', '2353fe49ff4ec9e08a5f9859e82061df4093dd817d1f6c7bade64f1df7ab3c1f'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/AsmApi.kt': ('adc4b888e21ea49c9d5712aa2dba6c12b4c56de187d7b7cb18fcd6a77e6bf2d2', 'adc4b888e21ea49c9d5712aa2dba6c12b4c56de187d7b7cb18fcd6a77e6bf2d2'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/BytecodeSignatureReader.kt': ('8d4716036ef08ef6745252f9d7b7b773ae9424518ae070aaa3d521a97db2bb87', '8d4716036ef08ef6745252f9d7b7b773ae9424518ae070aaa3d521a97db2bb87'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/ConstantBoxes.kt': ('71d52b66e7b9a46287100a06d5300067a53aa299fb50cd57004b30fda3c567fb', '71d52b66e7b9a46287100a06d5300067a53aa299fb50cd57004b30fda3c567fb'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/ConstantEquality.kt': ('80ded92d2460810b570ec2d4166abc4d5d6e707dd82110811f042449dacdefe9', '80ded92d2460810b570ec2d4166abc4d5d6e707dd82110811f042449dacdefe9'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/ConstantFolding.kt': ('8a35ee1f5abf027798bdb5c4b63f2c302dcc936d2b5c0af19eef59805db67fe2', '27a39419e735e15c99d7e033ff602ee4e749a6ef8f057c476b8fe2eb6c7529d7'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/DeclaredTypesReader.kt': (None, '74f15f91e41791e2b3d035de1c3dd3f3754feb5d6c7d431a123e53959a986ae7'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/EnumInitializationSafety.kt': ('544aefce9cedc0d38c3c571ebf51bfcb90b30b0e76d79a9a3892b268fd81252b', '544aefce9cedc0d38c3c571ebf51bfcb90b30b0e76d79a9a3892b268fd81252b'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/FoldDependencies.kt': ('b785fd6a2dde0e26306ddf0ee70694f74509eb22a3049409235456d04559bb72', 'b785fd6a2dde0e26306ddf0ee70694f74509eb22a3049409235456d04559bb72'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/FunctionalDispatch.kt': ('c7fda944ba780100757fa8c911b8143505e2ff53bdab4e0693d92e7c9e40de73', 'c7fda944ba780100757fa8c911b8143505e2ff53bdab4e0693d92e7c9e40de73'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/GenericSignatureParser.kt': ('7c5fd6054ba5f71b490560dcfbf22e83f3ac679c365b48b3a1dbacaec6f99cce', '7c5fd6054ba5f71b490560dcfbf22e83f3ac679c365b48b3a1dbacaec6f99cce'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/GraphiteContext.kt': ('5131145b205aa7a929b6dd4f9484b40120974f4c6eb2315a770bb93b2f1b1d87', '5131145b205aa7a929b6dd4f9484b40120974f4c6eb2315a770bb93b2f1b1d87'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/GraphiteExtension.kt': ('e652b9c4d45075b5fbe9a232dd59d32592f799bd27728f08807dc7f92754e41b', 'e652b9c4d45075b5fbe9a232dd59d32592f799bd27728f08807dc7f92754e41b'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/GraphiteJavaView.kt': ('ef430c972a9d057a4a0984a1d51d824713792544e75a8422edf58788e2446ed5', 'ef430c972a9d057a4a0984a1d51d824713792544e75a8422edf58788e2446ed5'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/InheritedFieldTypes.kt': (None, 'b3c8aadbcceccbb471b1bcb3219b830bd1266e48f5ddd0285d66792ab4ecf1c5'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/InterproceduralDataflow.kt': ('473c37bdb80d830ab5461f0258525196b0343afaf965f00a421a7ede8f4b573d', '473c37bdb80d830ab5461f0258525196b0343afaf965f00a421a7ede8f4b573d'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/JavaProjectLoader.kt': ('f8a9007b49e6bb6588c8f1ec8c14456b5efe484efcaaed9b984c3ae73d866c64', 'f8a9007b49e6bb6588c8f1ec8c14456b5efe484efcaaed9b984c3ae73d866c64'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/NonThrowingReplacement.kt': ('a4b09441067a60b7ef69b0ad6037cd5289f0f9ab9be98f68df24e5f126695d0d', 'a4b09441067a60b7ef69b0ad6037cd5289f0f9ab9be98f68df24e5f126695d0d'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/ParsedClassLocation.kt': ('b3f4bbc54d8ad92871ffb09015cbb325a8e4adb633272f08f806a0b97b26b3ef', '836f02386a9ede6264b83644ba24c2fbf8a4de567ec5a48e4ba30b4a72a610f9'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/RecoveringBodyInterceptor.kt': ('c631b4ec59af59beaa434e614078f8596cc0b7c583933730ca668b054cd790a8', 'c631b4ec59af59beaa434e614078f8596cc0b7c583933730ca668b054cd790a8'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/SootUpAdapter.kt': ('1e629c76bca692547d3f774bb2965032e81c82017437d73222937059da9e4712', '69c0fe3c66d59f3e7d798d6fc22074db9ea14f7cbae689bb8d7406de47131632'),
    'frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/SyntheticIdentity.kt': ('43c08a5bfbb2ae2011b2005c64c36610ba7182c3b383ea2f54593b78c76527be', '43c08a5bfbb2ae2011b2005c64c36610ba7182c3b383ea2f54593b78c76527be'),
    'frontend/jvm/sootup/src/main/kotlin/sootup/java/bytecode/frontend/conversion/GraphiteAsmClassSource.kt': ('3cb107c4f8dd6cf68cb173de8e51bba4796861f75835e0a10d3376ea6c31fb08', 'b905c9bba39a463ad65177e9a835d8a1700af29fc076c662574f303f8ee0d898'),
    'frontend/jvm/sootup/src/main/kotlin/sootup/java/bytecode/frontend/conversion/GraphiteClassNode.kt': ('ffe98f0110b061a993a368fac7145c661230e0b8e48dcc30f34a454964d7e48d', '17545896b05a4722d80fe2de9ec7df552c1ef796dc1b8e9d9974e424f1f15232'),
    'frontend/jvm/sootup/src/main/kotlin/sootup/java/core/GraphiteIdentifierFactory.kt': ('6e62873130271e33e34d210e2167060ff096df9aeb7cbeb35d6cbcc4d2480ec0', '6e62873130271e33e34d210e2167060ff096df9aeb7cbeb35d6cbcc4d2480ec0'),
    'frontend/jvm/webgraph/build.gradle.kts': ('f85addc3bc20c5b6efe27a008f4e40cd01aca594ae9b5e7cb19ee5dec068c307', '23818b1a419c7b5f39cd926c2c7371ea500f7ec61db147799fbc6f47ca3503bc'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/BinaryEncoding.kt': ('76d423233a2e26d90282421a94f23ee88d3dc391fd07f230be3d27f9cfd094a8', '76d423233a2e26d90282421a94f23ee88d3dc391fd07f230be3d27f9cfd094a8'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/BranchComparisonLookup.kt': ('04b2431f78f5f7d9a292b72f9e151feb9e65eeeda622aac78be3b347e0476f27', '04b2431f78f5f7d9a292b72f9e151feb9e65eeeda622aac78be3b347e0476f27'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/CallSiteIndexPersistenceInput.kt': ('5820fa51800efe82f1831e1a397ae5369cb76729b8e842a04f099a0ba30566fd', '5820fa51800efe82f1831e1a397ae5369cb76729b8e842a04f099a0ba30566fd'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/CallSiteOrdinalPersistenceInput.kt': ('d61934e5aaf6c84677a4d01d04bd62d76eb17a8cdd7a7f26f7109d6c8ba645d3', 'd61934e5aaf6c84677a4d01d04bd62d76eb17a8cdd7a7f26f7109d6c8ba645d3'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/CallSiteOrdinals.kt': ('a442b5ae38fd3e361f54ddff13f22e70a9edc1afd4a72187d6228aa7000450e6', 'a442b5ae38fd3e361f54ddff13f22e70a9edc1afd4a72187d6228aa7000450e6'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/ClassOverviewStore.kt': ('3fd57feb2c4cee1c8bab88f3646c35b77023d33b66cac22db2e840b0f49ac6ef', '3fd57feb2c4cee1c8bab88f3646c35b77023d33b66cac22db2e840b0f49ac6ef'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/DeclaredTypeAtoms.kt': (None, '4bf675e3ecd639fef5fc9ad177fda2930f10835cef2aefca0ded2403f3a030ec'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/DeclaredTypeProjectionRows.kt': (None, 'c020494f97c3354f75eecc423022e4217217ffded30271f06dbed719eb7f4498'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/DeclaredTypeSavePlan.kt': (None, '158b1cbb2bf616791e7d2519404e36943c98efe5214438a36ee3bb77fc412bbf'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/DeclaredTypeStore.kt': (None, 'ba55d4a1fab5baaba9afc0fac6a630e2184cb5c5bd6521e3c01a38a17b31e00c'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/DeclaredTypeStrings.kt': (None, 'f8a1eced3a201e85983f4582e12bf9346ca9eda3e6be2cd74a92168d8b83c87c'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/DeclaredTypeTextCandidates.kt': (None, 'a384f0e41db680f34e8c0774336ee78f39353fc8c5375de0aabdd2ad8ba3f27b'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/DeclaredTypeTextSummary.kt': (None, '7d4033fdf36971d9bb20aa21b09ce9f9d5a842d8aca4e11c1ab55f9a322ce513'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/ErasedDeclaredTypes.kt': (None, '7919f4a3d636ec7ccbfade3974f4062374a826c77a4360281bb36ac5928188e8'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/GraphStore.kt': ('ee70cabb62f5fe6feea3ed5f2f9e513050eb625473109c99aa4d9b8164dce3a0', 'f97f8992b4974b4fd3d1388c1f3c2dd18846e552d6c6cb47911cd900dd77ae26'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndex.kt': ('24b1defc81fb08f98b6316fea31ee4040ccb4135bed14591d97586e7053952f7', '24b1defc81fb08f98b6316fea31ee4040ccb4135bed14591d97586e7053952f7'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt': ('841699b6e7b06fd540ee2b56f711443c1c5b6ba3bde4dc8151f346d9ee5b4f81', '841699b6e7b06fd540ee2b56f711443c1c5b6ba3bde4dc8151f346d9ee5b4f81'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedDeclaredTypeValidationAccess.kt': (None, 'ed81905fe985e72a397832dfd595b1227caa25d2a942092af008cc2aaf172859'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedMethodIndex.kt': ('60ef0ffc93cfc9f4b0b47ecfd722238d782f4974556642431cee2962c3111261', '60ef0ffc93cfc9f4b0b47ecfd722238d782f4974556642431cee2962c3111261'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedPropertyTextCandidates.kt': ('a1e1c5d45ace145bef53e5141c2396385435ae8d64da1580a39db77fd3b7e563', '4fbbcbd01511852f1037d07752adb8f24018e2d410aaf470f6d641e325fd232c'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt': ('2c35efdaac10e775175fd0b567901a07bb63acdd73f071e289bb4195f8fa6ae2', 'f687155721aa485aaf37999ac19b70bf5ad7e89dabc6e75be685d0fe9dca312a'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeOffsetIndex.kt': ('b0be23221c4b6736f53cd72da6b325d40cce93042af8c63beb3831806c4d69a3', 'b0be23221c4b6736f53cd72da6b325d40cce93042af8c63beb3831806c4d69a3'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeSerializer.kt': ('d3260eedfac8d1d5a2f223d3b30ee421aebf4586daf0e1c5b028e04f0881d511', 'd3260eedfac8d1d5a2f223d3b30ee421aebf4586daf0e1c5b028e04f0881d511'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeTypeIndex.kt': ('d7312a3efe66e9e5f00f7fd41d79aca2c8926f8594d98c53c2be563a1acaafe2', 'd7312a3efe66e9e5f00f7fd41d79aca2c8926f8594d98c53c2be563a1acaafe2'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/PersistedResourceAccessor.kt': ('ca459c5371c172d8e96cd7bf73994a15fe769788ae1d37274e3fc31e7f43e6f5', 'ca459c5371c172d8e96cd7bf73994a15fe769788ae1d37274e3fc31e7f43e6f5'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/QueryCorrectnessManifest.kt': ('f6e8af6000ccd89e02ac932bf7e95c75cacce636ebcc73227e997f695f06e717', 'f6e8af6000ccd89e02ac932bf7e95c75cacce636ebcc73227e997f695f06e717'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/SharedDeclaredTypeTextMatcher.kt': (None, 'cdd3fe24ece67f5c92718fce4c25d5d3cf06353b0be674bf617654e0034cfcc2'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/StringTable.kt': ('d7dd6b85cce88f9202ce1e2b4087a0e6ac07211d1e52758f4964be48f2df18a8', '1541cc0d5f2edc3e307214ca9cdfd2c5388d9c2fd3847664de01ecb89b513436'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/StructuralDeclaredTypes.kt': (None, '244f0464f0a3517a419761e4447d2af1eedeb56edc384baa7dbf450035c4d295'),
    'frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/WebGraphBackedGraph.kt': ('c5ef49f7bce9296bc0518552f9c8224d7d68d2b30baad0dc49600224e6b15b4f', '8f63ecf11ae46bb0a2a095d4a32996cc5aea4234ab7dd0057abd33d228e89c3d'),
    'gradle.properties': ('bc0da5cf9329a78157ccee693c5b62bff1ac0eef9d9984846dfd8b213f3f2cc0', 'bc0da5cf9329a78157ccee693c5b62bff1ac0eef9d9984846dfd8b213f3f2cc0'),
    'gradle/libs.versions.toml': ('f118a54958a0448aa8b5e1b465a2a9949fd431b5553ba48eac245a77865ffc87', 'f118a54958a0448aa8b5e1b465a2a9949fd431b5553ba48eac245a77865ffc87'),
    'gradle/wrapper/gradle-wrapper.jar': ('28b330c20a9a73881dfe9702df78d4d78bf72368e8906c70080ab6932462fe9e', '28b330c20a9a73881dfe9702df78d4d78bf72368e8906c70080ab6932462fe9e'),
    'gradle/wrapper/gradle-wrapper.properties': ('e0d4d0d0ac0721415c6661e9713ab4ab0ef9d0b687b006574a089dcda5a81ec2', 'e0d4d0d0ac0721415c6661e9713ab4ab0ef9d0b687b006574a089dcda5a81ec2'),
    'gradlew': ('52f7d03f603956bb91f3fb4f18e08578aab89cc98ec606dc8dd811337f5bce3d', '52f7d03f603956bb91f3fb4f18e08578aab89cc98ec606dc8dd811337f5bce3d'),
    'gradlew.bat': ('0dc553fa66b53404638a5cc51a2fbfbc4f00b661fdc00b52da3acb5ed6868342', '0dc553fa66b53404638a5cc51a2fbfbc4f00b661fdc00b52da3acb5ed6868342'),
    'settings.gradle.kts': ('77177fcc30a91e87a3dda17102347b05da95b0ea9a9f998f7f39b197c2de6052', '77177fcc30a91e87a3dda17102347b05da95b0ea9a9f998f7f39b197c2de6052'),
}
REVIEWED_AT = {'accepted4f': '4f2ccf33b969e684972e56b5e810034e6e67c1b3', 'declared5a': '5a2b7efafccd20a61b8eadd640176e912922fb3f'}
REVIEWED_HELPERS = {'jvm_pressure_oracles.py': '99e130fb8dc7cdeb88906c0ba7352ff8989ab6774974ca9b1a21c132730b0cb6', 'jvm_pressure_derivation.py': 'f9931190b22dd02f60d2aafd6e437ebaa9471350c8d05e3f1ffd5ad65409d049', 'jvm_pressure_distinct.py': '398dd5c085c95ab8cf6f3900bc08ea0131ba3ab7919c8876ed5375274fd30faf', 'jvm_pressure_inputs.py': 'b9f2842ba45f4c42381e36d7c6fa27717973bbefe881455c66270022bc199c17', 'jvm_primitive_facts.py': '90bc6f1d6b7efdda374d068ad955174bedfce6a7b6099ff117f145b40514e6d2', 'JvmPrimitiveFacts.java': 'b2555994b5119172ff62c12a137f4d3d1e1df8d4ebe2d6dc97e7c6b9f3eaa582', 'native_core_proof/__init__.py': '5e49ef50d972046206c6a7025a28bfb2ec6fd38f10db76d2818968476393c0a7', 'native_core_proof/legacy_wire.py': '596662d14042024761c6643c1bec751b0b95cb8b73d235ccbd833e8748e05115', 'native_core_proof/legacy_schema_wire.py': '709bc7d22ad74847749075c5be233578263932cb3ea62a95d6d6e405c4c4f18b', 'native_core_proof/wire_gty05.py': '051af6b89c9f6643864bcf000f469397d4bfed994f607dbaea8480ac758405ec', 'native_core_proof/declarations.py': 'd0e08387a3ca8ba7cfc57b13a5e7ec4712480f5e75c45a14fa1222b3e8a01925', 'native_core_proof/callsite_ordinals.py': '464b5832217f8c2cad0537caad46a34d1a478cb03cc3a303acc4b715f8599fc3'}
REQUEST_CONTRACT_SHA = '6e0f62cea140385010f55ca8517d5ccabb5f8d7271e410951c896133f1184a85'


def kt(module, package, name):
    return f'frontend/jvm/{module}/src/main/kotlin/io/johnsonlee/graphite/{package}/{name}.kt'


RULES = {
    'raw-node-values-and-descriptors': (
        'Node tags retain primitive/enum payloads; enum text is owner.name; method signatures omit return but descriptors include it.',
        (kt('core', 'core', 'Node'), kt('webgraph', 'webgraph', 'NodeSerializer'))),
    'properties-labels-and-dynamic-keys': (
        'Fixed accessor precedence, annotation map key/value distinction, concrete labels plus aliases, qualified graph keys, and declared keys only for actual bindings.',
        tuple(kt('cypher', 'cypher', n) for n in ('NodePropertyAccessor', 'CypherFunctions', 'DynamicPropertyContains'))),
    'predicates-projections-and-limit': (
        'The fixed26 grammar/AST uses String-only CONTAINS, coalesce plus ROOT lowercase, exact graph filters, complete projected rows and legal LIMIT; no ORDER is added.',
        tuple(kt('cypher', 'cypher', n) for n in ('ParsedQuery', 'CypherClause', 'CypherPattern', 'ImmutableCypherAst',
                                               'ExpressionEvaluator', 'SourcePredicatePushdown', 'QueryPipeline')) +
        ('frontend/jvm/cypher/src/main/antlr/CypherLexer.g4', 'frontend/jvm/cypher/src/main/antlr/CypherParser.g4')),
    'qualified-identity-and-complete-provenance': (
        'Qualified id is graphId:localId; pre-Gson DISTINCT merges full graph provenance; output metadata graph IDs are sorted.',
        tuple(kt('cypher', 'cypher', n) for n in ('CrossGraphValues', 'CypherExecutor', 'QueryPipeline'))),
    'distinct-numeric-whole-row-representatives': (
        'cypherValueKey strips BigDecimal trailing zeroes; Float/Double use bound JDK toString; annotation fastpath guard reaches normalized DISTINCT; only actual whole-row JSON representatives are legal.',
        tuple(kt('cypher', 'cypher', n) for n in ('CypherValueSemantics', 'CypherExecutor', 'QueryPipeline'))),
    'full-response-and-gson': (
        'Explicit64 cross-graph request returns its64 graphs in the seven-field envelope; default Gson2.11 omits map nulls, preserves array nulls and reflects EnumValueReference fields.',
        (kt('explore', 'cli', 'ExploreRoutes'), kt('explore', 'cli', 'CypherResponseSerializer'),
         'gradle/libs.versions.toml', 'frontend/jvm/explore/build.gradle.kts', 'frontend/jvm/query/build.gradle.kts')),
    'explicit64-two-graph-routing': (
        'Explicit64 skips outer source-scope pushdown but exact non-DISTINCT four-clause route still selects the conjunctive graphId pair before residual OR; envelope remains requested64.',
        (kt('explore', 'cli', 'GraphRegistry'), kt('explore', 'cli', 'ExploreRoutes'), kt('cypher', 'cypher', 'QueryPipeline'))),
    'original-dataflow-labels': (
        'V3 family is low3 bits; DATAFLOW kind is (label>>3)&15 with nine valid ordinals; source and target remain original graph-local IDs.',
        (kt('core', 'core', 'Edge'), kt('webgraph', 'webgraph', 'NodeSerializer'), kt('cypher', 'cypher', 'CypherFunctions'))),
    'complete-return-inclusive-method-metadata': (
        'Virtual Method nodes enumerate complete persisted metadata using full descriptors, not a signature-deduplicated callsite subset.',
        (kt('core', 'graph', 'Graph'), kt('webgraph', 'webgraph', 'MappedMethodIndex'),
         kt('webgraph', 'webgraph', 'MappedWebGraphBackedGraph'), kt('webgraph', 'webgraph', 'WebGraphBackedGraph'),
         kt('cypher', 'cypher', 'MethodQueryExecutor'), kt('cypher', 'cypher', 'CrossGraphValues'))),
    'declared-property-presence-and-rendering': (
        '4f has no declared-property API/table. Candidate joins fields by owner/name/descriptor and method return/parameter/formal bindings; render/info preserve structural owner/argument/component/scope rules and validation.',
        tuple(kt('core', 'graph', n) for n in ('DeclaredTypeTable', 'DeclaredTypeReferences', 'ImmutableDeclaredTypeStorage',
                                              'DeclaredTypeExpansionBudget', 'DeclaredTypeValidationAccess')) +
        tuple(kt('cypher', 'cypher', n) for n in ('DeclaredTypeProperties', 'DeclaredPropertyPresence')) +
        (kt('webgraph', 'webgraph', 'DeclaredTypeStore'),)),
    'packaged-http-runtime-and-build': (
        'The owned build creates both :query:shadowJar HTTP runtime and :webgraph:jmhJar writer from the same clean source/runtime manifest; actual fixed JDK primitive facts are separately replayed.',
        (kt('query', 'cli', 'Main'), kt('query', 'cli', 'GraphiteCommand'),
         'build.gradle.kts', 'settings.gradle.kts', 'gradle/libs.versions.toml',
         'frontend/jvm/query/build.gradle.kts', 'frontend/jvm/webgraph/build.gradle.kts')),
}


def relevant_source(path):
    return (path in BUILD or path.startswith(('gradle/', 'buildSrc/', 'build-logic/')) or
            path.startswith('frontend/jvm/') and (path.endswith('/build.gradle.kts') or
            any(path.startswith('frontend/jvm/'+module+'/src/main/') for module in MODULES)))


def profiles():
    return {name: {path: hashes[index] for path, hashes in SOURCE_BYTES.items() if hashes[index] is not None}
            for index, name in enumerate(REVIEWED_AT)}


def recognized(source, pins):
    root = Path(source['root'])
    require(root.is_absolute() and str(root.resolve()) == str(root), 'canonical source checkout root')
    selected = {}
    for path, digest in source['files'].items():
        file = Path(path)
        require(file.is_absolute() and file.is_relative_to(root) and str(file) == str(file.resolve()) and
                common.valid_digest(digest) and pins.get(path) == digest, 'actual source file binding')
        relative = file.relative_to(root).as_posix()
        if relevant_source(relative): selected[relative] = digest
    matches = [name for name, expected in profiles().items() if selected == expected]
    require(len(matches) == 1, 'unrecognized closed JVM source/build profile')
    return matches[0], selected


def pinned_ref(plan, path):
    path = Path(path).resolve(); digest = plan['pins'].get(str(path))
    require(common.valid_digest(digest), 'required actual evidence pin: '+str(path))
    return {'path': str(path), 'sha256': digest}


def audit(plan):
    """Pure applicability; caller must separately replay the actual derivation."""
    require(plan['schema'] == 'graphite.jvm-raw-oracle-plan.v1' and
            re.fullmatch('[0-9a-f]{40}', plan['revision']) and
            plan['role'] in ('accepted-baseline', 'parent', 'candidate'), 'actual raw derivation plan identity')
    source = common.pinned_authority_metadata(plan, plan['sourceManifest'])
    runtime = common.pinned_authority_metadata(plan, plan['runtimeManifest'])
    require(source['revision'] == runtime['revision'] == plan['revision'] and
            runtime['sourceManifestSha256'] == plan['sourceManifest']['sha256'] and
            runtime['sourceFiles'] == source['files'], 'same actual source/runtime revision and complete files')
    profile, selected = recognized(source, plan['pins'])
    require(plan['role'] != 'accepted-baseline' or profile == 'accepted4f' and
            plan['revision'] == REVIEWED_AT['accepted4f'], 'accepted baseline remains exact4f')
    root = Path(source['root']); producer_root = Path(plan['producerRoot']).resolve()
    artifact_ref = pinned_ref(plan, producer_root/'artifact-audit.json')
    artifact = common.pinned_authority_metadata(plan, artifact_ref)
    require(artifact['sourceManifest'] == plan['sourceManifest'] and artifact['runtimeManifest'] == plan['runtimeManifest'] and
            artifact['revision'] == plan['revision'] and artifact['role'] == plan['role'], 'same owned producer source/runtime audit')
    require(runtime['toolchainIdentity']['java'] == plan['java'] and plan['jdkImage']['home'] == str(Path(plan['java']).resolve().parent.parent),
            'same actual primitive/runtime JDK')
    require(set(plan['jdkImage']['files']) == {'bin/java', 'release', 'lib/modules'} and
            all(ref == pinned_ref(plan, Path(plan['jdkImage']['home'])/name)
                for name, ref in plan['jdkImage']['files'].items()), 'complete JDK image bindings')
    packaged = {name: pinned_ref(plan, producer_root/'runtime'/file)
                for name, file in (('graphiteJar', 'graphite.jar'), ('writerJar', 'writer.jar'))}
    require(plan['writerJar'] == packaged['writerJar']['path'] and
            all(runtime['files'].get(ref['path']) == ref['sha256'] for ref in packaged.values()), 'actual packaged JVM runtime and writer')
    originals = runtime['originalArtifacts']
    require(originals.get(str(root/'frontend/jvm/query/build/libs/graphite.jar')) == packaged['graphiteJar']['sha256'],
            'packaged HTTP JAR is actual source-built artifact')
    writers = [path for path in originals if Path(path).parent == root/'frontend/jvm/webgraph/build/libs' and path.endswith('-jmh.jar')]
    require(len(writers) == 1 and originals[writers[0]] == packaged['writerJar']['sha256'] and
            all(plan['pins'].get(path) == digest for path, digest in originals.items()), 'exact source-built writer/runtime originals')
    phase_ref = pinned_ref(plan, producer_root/'build-jvm/record.json')
    require(phase_ref == artifacts.ref(phase_ref['path']), 'actual owned JVM build receipt bytes')
    argv = [str(root/'gradlew'), '--no-daemon', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx4g -XX:ActiveProcessorCount=4',
            '-Pkotlin.compiler.execution.strategy=in-process', ':webgraph:jmhJar', ':query:shadowJar']
    phase = artifacts.check_phase(Path(phase_ref['path']), argv, root)
    require(phase['name'] == 'build-jvm' and phase['timeoutSeconds'] == 7200, 'reviewed bounded JVM build')
    controls = Path(__file__).resolve().parent
    helpers = {str(controls/name): digest for name, digest in REVIEWED_HELPERS.items()}
    require(all(plan['pins'].get(path) == digest == common.sha(path) for path, digest in helpers.items()), 'reviewed exact oracle rule implementation closure')
    cases = model.cases()
    require(hashlib.sha256(json.dumps(cases, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest() ==
            REQUEST_CONTRACT_SHA, 'reviewed exact26 request contracts')
    rules = []
    for name, (statement, paths) in RULES.items():
        require(all(path in SOURCE_BYTES for path in paths), 'every named source rule has reviewed bytes')
        rules.append({'id': name, 'statement': statement,
                      'sources': [{'path': str(root/path), 'sha256': selected[path]} for path in paths if path in selected],
                      'absentSources': [str(root/path) for path in paths if path not in selected]})
    assigned = {path for _, paths in RULES.values() for path in paths}
    rules.append({'id': 'closed-runtime-source-and-build-dependencies',
                  'statement': 'Unlisted runtime-support sources/resources and build configuration must match the same reviewed product closure; unknown additions or changes fail closed.',
                  'sources': [{'path': str(root/path), 'sha256': digest} for path, digest in sorted(selected.items()) if path not in assigned],
                  'absentSources': []})
    evidence = {str(root/path): digest for path, digest in selected.items()}
    evidence.update(helpers)
    for ref in (plan['sourceManifest'], plan['runtimeManifest'], artifact_ref, phase_ref, *packaged.values(), *plan['jdkImage']['files'].values()):
        evidence[ref['path']] = ref['sha256']
    evidence.update(originals)
    for name in ('owner.json', 'stdout.log', 'stderr.log'):
        path = producer_root/'build-jvm'/name; ref = pinned_ref(plan, path)
        require(ref == artifacts.ref(path), 'owned JVM build evidence bytes'); evidence[str(path)] = ref['sha256']
    self_ref = artifacts.ref(__file__); evidence[self_ref['path']] = self_ref['sha256']
    return {'schema': 'graphite.jvm-source-rule-applicability.v1', 'status': 'PASS_REVIEWED_JVM_SOURCE_RULE_APPLICABILITY',
            'revision': plan['revision'], 'profile': profile, 'reviewedAt': REVIEWED_AT[profile],
            'sourceManifest': plan['sourceManifest'], 'runtimeManifest': plan['runtimeManifest'],
            'buildJvmPhase': phase_ref, 'packagedRuntime': packaged, 'rules': rules,
            'requestContracts': [{key: case[key] for key in ('id', 'requestSha256', 'querySha256', 'requestedGraphIds', 'targetGraphIds')}
                                 for case in cases], 'pins': evidence,
            'sourceRuleApplicabilityVerified': True, 'upstreamExecutionReplayRequired': True,
            'oracleAuthorityVerified': False, 'fresh64Acceptance': False, 'performanceAcceptance': False}
