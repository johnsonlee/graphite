import pathlib,subprocess,json
b=pathlib.Path(__file__).parent;jar='/tmp/graphite-go-main-baseline-clone-4e328b0/graphite-explore/build/libs/graphite-explore.jar';results=[]
for kind in ['normal','ratio1','ratio128','long-prefix','surrogates']:
 p=b/(kind+'.strings');j=subprocess.run(['java','-Xmx128m','-cp',str(b)+':'+jar,'StringFormats',str(p),kind],capture_output=True,text=True,check=True);g=subprocess.run([str(b/'go-probe'),'strings',str(p)],capture_output=True,text=True,check=True)
 row={'case':kind,'jvm':json.loads(j.stdout),'go':json.loads(g.stdout)};row['equal']=row['go']==row['jvm'];results.append(row);print(row)
(b/'strings-results.json').write_text(json.dumps(results,indent=2)+'\n')
