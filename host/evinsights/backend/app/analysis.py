import numpy as np
import pandas as pd
import statsmodels.formula.api as smf
from statsmodels.stats.outliers_influence import variance_inflation_factor


def regression(trips, spec):
    features=spec.get('features',['temp_mean_k','avg_speed_m_s','distance_m'])
    allowed={'temp_mean_k','avg_speed_m_s','distance_m','elevation_gain_m','local_hour','hvac_on_fraction'}
    if not features or not set(features)<=allowed:
        raise ValueError('Unsupported features')
    form=spec.get('form','linear')
    if form not in ('linear','polynomial','spline','binned'): raise ValueError('Unsupported form')
    records=[]; excluded=0
    for trip in trips:
        if trip['state']!='COMPLETE': excluded+=1; continue
        m=trip['metrics']; y=m.get('energy_per_distance_j_m',{}).get('value')
        vals={f:m.get(f,{}).get('value') for f in features}
        if y is None or any(v is None for v in vals.values()): excluded+=1; continue
        records.append({'y':y,**vals,'trip_id':str(trip['id']),'route':trip.get('route_id') or 'unmatched'})
    df=pd.DataFrame(records)
    # Constrain complexity before fitting, then check the actual design-matrix rank.
    terms=[]
    for f in features:
        nonlinear=f in ('temp_mean_k','avg_speed_m_s')
        terms.append(f'bs({f}, df=3, degree=2)' if form=='spline' and nonlinear else
                     f'{f} + I({f} ** 2)' if form=='polynomial' and nonlinear else
                     f'C(pd.cut({f}, 3))' if form=='binned' and nonlinear else f)
    if spec.get('route',True): terms.append('C(route)')
    formula='y ~ '+' + '.join(terms)
    base={'n':len(records),'excluded':excluded,'formula':formula,'kind':'inferential',
          'interpretation':'Associations in observed trips, not causal effects. Route adjustment cannot remove unmeasured confounding.'}
    if len(records)<max(20,10*(len(features)+1)):
        return {**base,'status':'insufficient_data','reason':'AT_LEAST_10_TRIPS_PER_PARAMETER_AND_20_TOTAL','coefficients':[]}
    model=smf.ols(formula,df).fit()
    matrix=model.model.exog; rank=np.linalg.matrix_rank(matrix); parameters=matrix.shape[1]
    if len(records)<10*parameters:
        return {**base,'status':'insufficient_data','reason':'MODEL_COMPLEXITY_EXCEEDS_SAMPLE_SIZE','parameters':parameters,'coefficients':[]}
    if rank<parameters:
        return {**base,'status':'invalid_design','reason':'RANK_DEFICIENT_COLLINEARITY','parameters':parameters,'coefficients':[]}
    ci=model.conf_int()
    coefs=[{'term':name,'coefficient':float(model.params[name]),'ci_low':float(ci.loc[name,0]),
            'ci_high':float(ci.loc[name,1]),'p_value':float(model.pvalues[name])} for name in model.params.index]
    vifs=[]
    for i,name in enumerate(model.model.exog_names):
        if name=='Intercept': continue
        v=variance_inflation_factor(matrix,i)
        vifs.append({'term':name,'vif':float(v) if np.isfinite(v) else None})
    return {**base,'status':'ok','parameters':parameters,'r_squared':float(model.rsquared),
            'coefficients':coefs,'collinearity_warning':bool(any(v['vif'] is None or v['vif']>5 for v in vifs)),
            'vif':vifs,'residuals':[{'trip_id':r['trip_id'],'fitted':float(f),'residual':float(e)} for r,f,e in zip(records,model.fittedvalues,model.resid)]}
