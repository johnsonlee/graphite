# 回退 CI：全部34查询原值

每条3组 base→revert。† 表示单次同时超过 >15% 与 >1 ms；本次无同查询两次越界。

| 查询 ID | Pair1 ms | Pair2 ms | Pair3 ms |
|---|---:|---:|---:|
| global-wide-four-properties-zero | 686.984334→683.171401 | 477.650001→510.695389 | 527.034466→534.786593 |
| global-wide-four-properties-targeted | 30.516193→24.191678 | 25.040026→25.516012 | 30.284408→27.518670 |
| global-wide-four-properties-dense | 12.023450→13.135332 | 13.757888→15.335369 | 19.412625→13.794904 |
| global-wide-class-pair-zero | 9.814903→6.913578 | 9.463548→10.203134 | 12.138583→10.530123 |
| global-wide-class-pair-targeted | 11.934005→12.076894 | 12.612563→14.428579 | 13.380428→17.001853 † |
| global-wide-class-pair-dense | 3.349139→2.836971 | 4.219720→2.852219 | 3.033467→3.170343 |
| global-wide-name-pair-zero | 11.641997→6.504671 | 7.632374→9.362247 † | 7.551334→7.438483 |
| global-wide-name-pair-targeted | 19.904235→11.200219 | 13.780783→18.149186 † | 17.984696→17.816982 |
| global-wide-name-pair-dense | 2.520768→2.815358 | 2.089829→1.569233 | 2.103145→2.257304 |
| global-wide-caller-class-zero | 8.162982→6.559465 | 6.325014→7.113661 | 7.651132→6.442656 |
| global-wide-caller-class-targeted | 7.733246→8.663221 | 8.714913→9.658132 | 9.178187→14.894500 † |
| global-wide-caller-class-dense | 1.712842→1.612174 | 1.067412→1.235778 | 3.224391→2.848185 |
| global-wide-callee-class-zero | 4.758495→5.392675 | 6.720764→4.744738 | 9.150840→7.522058 |
| global-wide-callee-class-targeted | 8.007490→6.010304 | 7.011288→7.288278 | 8.101158→9.616812 † |
| global-wide-callee-class-dense | 1.311571→0.981230 | 1.093601→0.970831 | 1.228244→0.959170 |
| global-wide-provenance-zero | 4.090722→4.303903 | 6.523394→3.792472 | 5.887925→10.021709 † |
| global-wide-provenance-targeted | 9.543973→6.986265 | 7.560819→8.616930 | 7.593668→8.400610 |
| global-wide-provenance-dense | 2.778019→1.771800 | 2.041899→2.095811 | 2.017434→2.507273 |
| global-wide-aliased-zero | 3.457907→3.251930 | 3.337909→3.775872 | 3.816125→6.568853 † |
| global-wide-aliased-targeted | 11.944215→10.024851 | 12.470106→13.366349 | 15.103682→11.963171 |
| global-wide-aliased-dense | 2.423536→2.374254 | 1.817118→1.800196 | 3.604872→2.785473 |
| global-wide-parameterized-zero | 3.556381→3.506077 | 3.455871→3.323613 | 3.147391→3.140521 |
| global-wide-parameterized-targeted | 6.272456→6.094626 | 7.121174→6.598793 | 6.355956→9.046159 † |
| global-wide-parameterized-dense | 2.317098→1.719725 | 1.742578→1.783815 | 1.734486→2.158281 |
| global-wide-wrapped-case-insensitive-zero | 7.040956→3.878549 | 4.437973→4.303393 | 4.901604→3.948306 |
| global-wide-wrapped-case-insensitive-targeted | 6.866791→7.344837 | 10.834448→9.353091 | 8.018762→7.754085 |
| global-wide-wrapped-case-insensitive-dense | 3.421518→3.317633 | 3.649120→3.571235 | 4.067373→4.329532 |
| global-wide-wrapped-case-insensitive-distinct-zero | 11.374527→9.207361 | 12.799343→12.049300 | 15.026297→13.791070 |
| global-wide-wrapped-case-insensitive-distinct-targeted | 60.992601→62.779121 | 66.547629→62.912081 | 70.155999→62.746552 |
| global-wide-wrapped-case-insensitive-distinct-dense | 119.915932→297.628775 † | 127.839829→118.118335 | 110.626223→117.182906 |
| global-wide-distribution-broad-all-64 | 2.181843→1.817689 | 2.270017→2.390313 | 1.843688→2.205688 |
| global-wide-distribution-localized-early | 5.425868→2.930647 | 5.718425→4.119093 | 3.921046→3.963194 |
| global-wide-distribution-localized-late | 13.396990→9.751758 | 18.118691→13.509367 | 12.044173→14.317600 † |
| global-wide-distribution-localized-middle | 7.509106→7.583980 | 13.838251→7.263291 | 7.057979→8.108931 |
