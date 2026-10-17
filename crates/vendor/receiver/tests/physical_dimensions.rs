use receiver::{info::{build_info, DeviceConfig}, vehicle_config::VehicleConfig};

fn display(yaml: &str) -> plist::Value {
    let cfg = VehicleConfig::from_yaml(yaml.as_bytes()).unwrap().apply(DeviceConfig::default());
    let info = plist::Value::from_reader(std::io::Cursor::new(build_info(&cfg))).unwrap();
    info.as_dictionary().unwrap()["displays"].as_array().unwrap()[0].clone()
}

#[test]
fn physical_calibration_does_not_change_coded_pixels() {
    for width in [133, 200, 400] {
        let yaml = format!("displayPanelsConfig:\n  mainDisplayPanel:\n    pixelDimensions: {{width: 1024, height: 1100}}\n    physicalDimensions: {{width: {width}, height: 215}}\n");
        let d = display(&yaml);
        let d = d.as_dictionary().unwrap();
        assert_eq!(d["widthPixels"].as_signed_integer(), Some(1024));
        assert_eq!(d["heightPixels"].as_signed_integer(), Some(1100));
        assert_eq!(d["widthPhysical"].as_signed_integer(), Some(width));
        assert_eq!(d["heightPhysical"].as_signed_integer(), Some(215));
    }
}

#[test]
fn absent_or_invalid_physical_dimensions_keep_unknown_defaults() {
    for fields in ["", "physicalDimensions: {width: -1, height: 50}",
        "physicalDimensions: {width: 100, height: 0}", "physicalDimensions: {width: 9000, height: 50}"] {
        let yaml = format!("displayPanelsConfig:\n  mainDisplayPanel:\n    {fields}\n");
        // An empty mapping must be explicit, not a YAML null panel.
        let yaml = if fields.is_empty() { "{}" } else { &yaml };
        let d = display(yaml);
        let d = d.as_dictionary().unwrap();
        assert_eq!(d["widthPhysical"].as_signed_integer(), Some(0));
        assert_eq!(d["heightPhysical"].as_signed_integer(), Some(0));
    }
}
