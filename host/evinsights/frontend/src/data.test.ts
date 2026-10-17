import {describe,it,expect} from 'vitest';
import {display,filterTrips,emptyFilters,Trip} from './data';
describe('display-only conversions',()=>{
 it('preserves missing values',()=>expect(display(null,'distance_m','us')).toBeNull());
 it('converts SI energy efficiency and temperature',()=>{expect(display(360,'energy_per_distance_j_m','metric')).toBe(10);expect(display(273.15,'temp_mean_k','us')).toBe(32);expect(display(1609.344,'distance_m','us')).toBe(1);});
 it('does not pass unknown distances through numeric filters',()=>{const t={id:'a',start_ts:'2026-01-01',metrics:{},confidence:1} as unknown as Trip;expect(filterTrips([t],{...emptyFilters,minDistance:'1'},'metric')).toEqual([]);});
});
