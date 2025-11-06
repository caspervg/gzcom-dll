//! Example: City Inspector
//!
//! This example demonstrates how to use the safe Rust bindings to inspect
//! various properties of a SimCity 4 city.
//!
//! Note: This is a demonstration of the API. It won't actually run without
//! being loaded by SimCity 4.

use sc4_rust_poc::{City, WorldPosition, CellCoord};
use std::error::Error;

/// Inspect and print city information
///
/// This function demonstrates the safe API - notice there's no `unsafe` anywhere!
fn inspect_city(city: &City) -> Result<(), Box<dyn Error>> {
    println!("=== City Inspector ===\n");

    // Basic city info
    println!("Basic Information:");
    println!("  Serial Number: {}", city.serial_number());
    println!("  Birth Date: {}", city.birth_date());
    println!("  Established: {}", city.is_established());
    println!("  Difficulty: {:?}", city.difficulty_level()?);
    println!();

    // Size and dimensions
    let size = city.size();
    let cells = city.cell_count();
    let cell_size = city.cell_size();

    println!("Dimensions:");
    println!("  World Size: {:.2} x {:.2}", size.width, size.height);
    println!("  Grid Size: {} x {} cells", cells.x, cells.z);
    println!("  Cell Size: {:.2} x {:.2}", cell_size.width, cell_size.height);
    println!();

    // Position and elevation
    let pos = city.world_position();
    let elevation = city.base_elevation();
    let hemisphere = city.hemisphere();

    println!("Location:");
    println!("  World Position: ({:.2}, {:.2})", pos.x, pos.z);
    println!("  Base Elevation: {:.2}", elevation);
    println!("  Hemisphere: {}", if hemisphere == 0 { "North" } else { "South" });
    println!();

    // Simulation state
    println!("Simulation:");
    println!("  Simulation Mode: {}", city.is_in_simulation_mode());
    println!("  Save Disabled: {}", city.is_save_disabled());
    println!("  UI Lock Count: {}", city.ui_lock_count());
    println!();

    Ok(())
}

/// Demonstrate coordinate conversion
fn demonstrate_coordinates(city: &City) -> Result<(), Box<dyn Error>> {
    println!("=== Coordinate Conversion Demo ===\n");

    // Pick a point in the middle of the city
    let size = city.size();
    let center = WorldPosition {
        x: size.width / 2.0,
        z: size.height / 2.0,
    };

    println!("Testing center position: ({:.2}, {:.2})", center.x, center.z);

    // Convert to cell
    if city.is_position_in_bounds(center) {
        let cell = city.position_to_cell(center)?;
        println!("  -> Cell coordinates: ({}, {})", cell.x, cell.z);

        // Convert back
        let corner = city.cell_corner_to_position(cell)?;
        println!("  -> Cell corner position: ({:.2}, {:.2})", corner.x, corner.z);

        let cell_center = city.cell_center_to_position(cell)?;
        println!("  -> Cell center position: ({:.2}, {:.2})", cell_center.x, cell_center.z);

        // Check bounds
        println!("  -> Cell in bounds: {}", city.is_cell_in_bounds(cell));
    } else {
        println!("  -> Position out of bounds!");
    }

    println!();
    Ok(())
}

/// Example of a mod that analyzes city coverage
///
/// This demonstrates how you might write actual mod logic using the safe API
fn analyze_city_coverage(city: &City) -> Result<(), Box<dyn Error>> {
    println!("=== Analyzing City Coverage ===\n");

    let cells = city.cell_count();
    let total_cells = cells.x * cells.z;

    println!("Total grid cells: {}", total_cells);

    // In a real mod, you'd iterate over cells and check occupancy, zoning, etc.
    // This would require bindings to LotManager, OccupantManager, etc.
    println!("(Cell-by-cell analysis would require additional manager bindings)");
    println!();

    // But we can still do some calculations with what we have
    let size = city.size();
    let cell_size = city.cell_size();
    let area = size.width * size.height;
    let cell_area = cell_size.width * cell_size.height;

    println!("City Statistics:");
    println!("  Total area: {:.2} square units", area);
    println!("  Average cell area: {:.2} square units", cell_area);
    println!("  Cells per square unit: {:.4}", total_cells as f32 / area);

    Ok(())
}

/// Example of safe resource management with UI locking
fn safe_ui_operation(city: &City) -> Result<(), Box<dyn Error>> {
    println!("=== Safe UI Operation with RAII ===\n");

    println!("UI lock count before: {}", city.ui_lock_count());

    // This demonstrates how you could use Rust's RAII for resource management
    {
        // Lock the UI
        city.ui_lock();
        println!("UI locked. Lock count: {}", city.ui_lock_count());

        // Do some work...
        println!("Performing UI-sensitive operation...");

        // In a real implementation, you'd use a guard struct:
        // struct UILockGuard<'a>(&'a City);
        // impl Drop for UILockGuard { ... }
        // This would guarantee the unlock happens even if there's a panic

        // Unlock
        let new_count = city.ui_unlock();
        println!("UI unlocked. Lock count: {}", new_count);
    }

    println!();
    Ok(())
}

/// Main function (won't actually run outside of SC4)
fn main() {
    println!("SimCity 4 Rust Bindings - City Inspector Example\n");
    println!("This example demonstrates the safe API for working with cISC4City.");
    println!("In a real mod, you would get the City instance from the game's App object.\n");

    // In a real mod, you'd get the city like this:
    // let app = get_sc4_app(); // From framework hooks
    // let city_ptr = app.GetCity();
    // let city = unsafe { City::from_raw(city_ptr).unwrap() };

    // For demonstration purposes, we'll just show what you would do:
    println!("// In your mod's PostAppInit hook:");
    println!("fn post_app_init(city: &City) -> Result<(), Box<dyn Error>> {{");
    println!("    inspect_city(city)?;");
    println!("    demonstrate_coordinates(city)?;");
    println!("    analyze_city_coverage(city)?;");
    println!("    safe_ui_operation(city)?;");
    println!("    Ok(())");
    println!("}}");
}
