from datetime import datetime
from math import isfinite
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictFloat, StrictInt, model_validator

SIGNALS = {
    'PERF_VEHICLE_SPEED': 'm/s', 'PERF_ODOMETER': 'm',
    'EV_BATTERY_LEVEL': 'J', 'INFO_EV_BATTERY_CAPACITY': 'J',
    'EV_BATTERY_INSTANTANEOUS_CHARGE_RATE': 'W',
    'ENV_OUTSIDE_TEMPERATURE': 'K', 'RANGE_REMAINING': 'm',
    'TIRE_PRESSURE': 'Pa', 'WHEEL_TICK': 'ticks', 'GEAR_SELECTION': 'enum',
    'IGNITION_STATE': 'enum', 'EV_CHARGE_PORT_CONNECTED': 'bool',
    'EV_CHARGE_STATE': 'enum', 'HVAC_POWER_ON': 'bool',
    'UX_RESTRICTIONS': 'bool', 'CLIENT_MARKER': 'enum', 'GPS_SPEED': 'm/s',
    'WALL_ENERGY': 'J', 'CHARGE_TYPE': 'enum',
}


class Event(BaseModel):
    model_config = ConfigDict(extra='forbid', allow_inf_nan=False, json_schema_extra={
        'oneOf': [{'required': [field], 'properties': {field: {'not': {'type': 'null'}}},
                   'not': {'anyOf': [{'required': [other], 'properties': {other: {'not': {'type': 'null'}}}} for other in
                                    ('value_num','value_int','value_bool','value_str','value_arr') if other != field]}}
                  for field in ('value_num','value_int','value_bool','value_str','value_arr')]
    })
    event_id: UUID
    batch_id: UUID
    schema_version: Literal[1]
    device_id: str = Field(min_length=1, max_length=128)
    vehicle_id: str = Field(min_length=1, max_length=128)
    source_ts: datetime
    source_elapsed_ns: int = Field(ge=0)
    tz_offset_min: int = Field(ge=-840, le=840)
    property_id: int = Field(ge=-(2**63), lt=2**63)
    property_name: str = Field(min_length=1, max_length=128)
    area_id: int = Field(ge=0)
    status: Literal['available', 'unavailable', 'error']
    value_num: StrictFloat | None = None
    value_int: StrictInt | None = None
    value_bool: StrictBool | None = None
    value_str: str | None = Field(default=None, max_length=10000)
    value_arr: list[Any] | None = Field(default=None, max_length=256)
    unit_canonical: str = Field(min_length=1, max_length=32)
    unit_original: str = Field(min_length=1, max_length=64)
    quality: Literal['measured', 'derived', 'enriched', 'user-entered']
    lat: float | None = Field(default=None, ge=-90, le=90)
    lon: float | None = Field(default=None, ge=-180, le=180)
    alt_m: float | None = None
    accuracy_m: float | None = Field(default=None, ge=0)
    bearing_deg: float | None = Field(default=None, ge=0, lt=360)
    app_version: str = Field(min_length=1, max_length=128)
    build_fingerprint: str = Field(min_length=1, max_length=512)
    seq: int = Field(ge=0)
    meta: dict[str, Any]

    @model_validator(mode='after')
    def invariants(self):
        if self.source_ts.utcoffset() is None or self.source_ts.utcoffset().total_seconds() != 0:
            raise ValueError('source_ts must have a UTC offset of zero')
        fields = ('value_num', 'value_int', 'value_bool', 'value_str', 'value_arr')
        if sum(getattr(self, k) is not None for k in fields) != 1:
            raise ValueError('exactly one non-null typed value is required')
        if (self.lat is None) != (self.lon is None):
            raise ValueError('latitude and longitude must be supplied together')
        if not isinstance(self.meta.get('boot_id'), str) or not self.meta['boot_id']:
            raise ValueError('meta.boot_id is required')
        def finite(v):
            if isinstance(v, float) and not isfinite(v):
                raise ValueError('non-finite number')
            if isinstance(v, dict):
                for x in v.values(): finite(x)
            if isinstance(v, list):
                for x in v: finite(x)
        finite(self.model_dump())
        if self.status == 'available' and self.property_name in SIGNALS:
            if self.unit_canonical != SIGNALS[self.property_name]:
                raise ValueError('incorrect canonical unit for known signal')
        return self

    def payload(self):
        return self.model_dump(mode='json', exclude_none=True)


class Batch(BaseModel):
    model_config = ConfigDict(extra='forbid')
    batch_id: UUID
    events: list[dict[str, Any]] = Field(min_length=1, max_length=5000)
