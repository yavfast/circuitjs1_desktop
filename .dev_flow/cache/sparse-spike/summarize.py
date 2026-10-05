import json,sys
for l in sys.stdin:
  if not l.startswith('{'): print(l.strip()); continue
  d=json.loads(l); a=d['analysis']; s=d['stepping']
  st=s['steps'] or 1
  print(d['spec'], 'm',a['factorN'], 'full',a['stamp'][0]['full'] if a['stamp'] else None, 'nnz',d['nnz'], 'nl', a['stamp'][0]['nonlinear'] if a['stamp'] else None)
  print('   analysis: wall',a['wallMs'],'stamps',len(a['stamp']),'stampMs',[round(x['ms']) for x in a['stamp']],'factors',a['factors'],'factorMs',a['factorMs'],'simplifyMs',[round(x['ms']) for x in a['simplify']])
  print('   stepping: wall',s['wallMs'],'steps',s['steps'],'ms/step',round((s['wallMs'] or 0)/st,2),'restamps',s['restamps'],'factors',s['factors'],'factorMs',s['factorMs'],'factors/step',round(s['factors']/st,2),'solve',s['solve'])
  if d.get('top'): print('   top', d['top'][:10])
