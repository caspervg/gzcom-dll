//! Example: Terrain Analyzer Mod
//!
//! This is a realistic example of a mod that analyzes terrain and provides
//! information to the player. It demonstrates:
//! - Safe Rust code doing actual computation
//! - Integration with SC4's callback system
//! - Rust's strengths: error handling, data structures, algorithms
//!
//! This showcases the 80/20 rule:
//! - 20% unsafe FFI wrapper (already done, reusable)
//! - 80% safe Rust logic (your mod code - shown below)

use sc4_rust_poc::{City, WorldPosition, CellCoord};
use std::collections::HashMap;
use std::error::Error;

/// Statistics about terrain in the city
#[derive(Debug, Clone, Default)]
pub struct TerrainStats {
    pub total_cells: usize,
    pub analyzed_cells: usize,
    pub min_elevation: f32,
    pub max_elevation: f32,
    pub avg_elevation: f32,
    pub flat_cells: usize,
    pub sloped_cells: usize,
    pub steep_cells: usize,
}

/// Categorization of terrain slope
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SlopeCategory {
    Flat,    // 0-5 degrees
    Sloped,  // 5-15 degrees
    Steep,   // 15+ degrees
}

/// A cell with its terrain information
#[derive(Debug, Clone)]
pub struct TerrainCell {
    pub coord: CellCoord,
    pub elevation: f32,
    pub slope: f32,
    pub category: SlopeCategory,
}

/// The main mod state
///
/// This is YOUR code - all safe Rust with your domain logic
pub struct TerrainAnalyzerMod {
    /// Cached terrain analysis
    terrain_data: HashMap<(i32, i32), TerrainCell>,
    /// Overall statistics
    stats: TerrainStats,
    /// Whether we've done the initial analysis
    initialized: bool,
}

impl TerrainAnalyzerMod {
    /// Create a new mod instance
    pub fn new() -> Self {
        Self {
            terrain_data: HashMap::new(),
            stats: TerrainStats::default(),
            initialized: false,
        }
    }

    /// Initialize the mod with city data
    ///
    /// This would be called from your PostAppInit hook
    /// Notice: 100% safe Rust, no unsafe anywhere!
    pub fn initialize(&mut self, city: &City) -> Result<(), Box<dyn Error>> {
        println!("Terrain Analyzer: Initializing...");

        // Get city dimensions
        let cells = city.cell_count();
        let base_elevation = city.base_elevation();

        println!("  City grid: {}x{} cells", cells.x, cells.z);
        println!("  Base elevation: {:.2}", base_elevation);

        // In a real mod, you'd get terrain data from cISTETerrain
        // For this example, we'll simulate the terrain analysis
        self.analyze_terrain(city, cells)?;

        self.initialized = true;
        println!("Terrain Analyzer: Initialization complete");
        println!("  Analyzed {} cells", self.stats.analyzed_cells);
        println!("  Elevation range: {:.2} - {:.2}",
                 self.stats.min_elevation, self.stats.max_elevation);

        Ok(())
    }

    /// Analyze terrain for all cells
    ///
    /// This demonstrates complex logic in safe Rust:
    /// - Iteration over city grid
    /// - Data structure population
    /// - Statistical computation
    fn analyze_terrain(&mut self, city: &City, cells: CellCoord) -> Result<(), Box<dyn Error>> {
        let total = (cells.x * cells.z) as usize;
        self.stats.total_cells = total;

        let mut sum_elevation = 0.0f32;
        let mut min_elev = f32::MAX;
        let mut max_elev = f32::MIN;

        // Iterate over all cells
        for z in 0..cells.z {
            for x in 0..cells.x {
                let coord = CellCoord { x, z };

                // Check bounds (safe!)
                if !city.is_cell_in_bounds(coord) {
                    continue;
                }

                // Get cell position
                let pos = city.cell_center_to_position(coord)?;

                // In reality, you'd call terrain.GetElevation(pos.x, pos.z)
                // For this demo, we'll simulate elevation
                let elevation = self.simulate_elevation(pos, city.base_elevation());

                // Calculate slope (would use actual neighbor cells in real mod)
                let slope = self.calculate_slope(pos, elevation);

                // Categorize
                let category = Self::categorize_slope(slope);

                // Update stats
                sum_elevation += elevation;
                min_elev = min_elev.min(elevation);
                max_elev = max_elev.max(elevation);

                match category {
                    SlopeCategory::Flat => self.stats.flat_cells += 1,
                    SlopeCategory::Sloped => self.stats.sloped_cells += 1,
                    SlopeCategory::Steep => self.stats.steep_cells += 1,
                }

                // Store in cache
                self.terrain_data.insert(
                    (x, z),
                    TerrainCell {
                        coord,
                        elevation,
                        slope,
                        category,
                    },
                );

                self.stats.analyzed_cells += 1;
            }
        }

        // Finalize stats
        if self.stats.analyzed_cells > 0 {
            self.stats.avg_elevation = sum_elevation / self.stats.analyzed_cells as f32;
            self.stats.min_elevation = min_elev;
            self.stats.max_elevation = max_elev;
        }

        Ok(())
    }

    /// Simulate elevation (in real mod, this comes from game API)
    fn simulate_elevation(&self, pos: WorldPosition, base: f32) -> f32 {
        // Simple simulation using position
        base + (pos.x * 0.1).sin() * 20.0 + (pos.z * 0.1).cos() * 15.0
    }

    /// Calculate slope at a position
    fn calculate_slope(&self, _pos: WorldPosition, _elevation: f32) -> f32 {
        // In real implementation:
        // 1. Get elevations of neighboring cells
        // 2. Calculate gradient
        // 3. Convert to degrees
        //
        // For demo, return random-ish value
        ((_pos.x + _pos.z) % 30.0) / 2.0
    }

    /// Categorize slope into terrain types
    fn categorize_slope(slope: f32) -> SlopeCategory {
        if slope < 5.0 {
            SlopeCategory::Flat
        } else if slope < 15.0 {
            SlopeCategory::Sloped
        } else {
            SlopeCategory::Steep
        }
    }

    /// Find best locations for building based on terrain
    ///
    /// This is the kind of algorithm that benefits from Rust:
    /// - Complex logic
    /// - Safe iteration
    /// - Type-safe results
    pub fn find_flat_areas(&self, min_size: usize) -> Vec<CellCoord> {
        let mut flat_areas = Vec::new();

        for ((x, z), cell) in &self.terrain_data {
            if cell.category == SlopeCategory::Flat {
                // Check if there's a flat region of minimum size
                if self.has_flat_region(*x, *z, min_size) {
                    flat_areas.push(CellCoord { x: *x, z: *z });
                }
            }
        }

        flat_areas
    }

    /// Check if there's a flat region around a cell
    fn has_flat_region(&self, center_x: i32, center_z: i32, size: usize) -> bool {
        let radius = (size / 2) as i32;

        for z in (center_z - radius)..=(center_z + radius) {
            for x in (center_x - radius)..=(center_x + radius) {
                if let Some(cell) = self.terrain_data.get(&(x, z)) {
                    if cell.category != SlopeCategory::Flat {
                        return false;
                    }
                } else {
                    return false;
                }
            }
        }

        true
    }

    /// Get terrain at specific coordinates
    pub fn get_terrain(&self, coord: CellCoord) -> Option<&TerrainCell> {
        self.terrain_data.get(&(coord.x, coord.z))
    }

    /// Get overall statistics
    pub fn get_stats(&self) -> &TerrainStats {
        &self.stats
    }

    /// Generate a report (demonstrates string processing in safe Rust)
    pub fn generate_report(&self) -> String {
        let mut report = String::new();

        report.push_str("=== Terrain Analysis Report ===\n\n");

        report.push_str(&format!(
            "Total Cells: {} ({} analyzed)\n",
            self.stats.total_cells, self.stats.analyzed_cells
        ));

        report.push_str(&format!(
            "Elevation: {:.2} (min) | {:.2} (avg) | {:.2} (max)\n\n",
            self.stats.min_elevation, self.stats.avg_elevation, self.stats.max_elevation
        ));

        let total = self.stats.analyzed_cells as f32;
        let flat_pct = (self.stats.flat_cells as f32 / total) * 100.0;
        let sloped_pct = (self.stats.sloped_cells as f32 / total) * 100.0;
        let steep_pct = (self.stats.steep_cells as f32 / total) * 100.0;

        report.push_str("Terrain Distribution:\n");
        report.push_str(&format!(
            "  Flat:   {} cells ({:.1}%)\n",
            self.stats.flat_cells, flat_pct
        ));
        report.push_str(&format!(
            "  Sloped: {} cells ({:.1}%)\n",
            self.stats.sloped_cells, sloped_pct
        ));
        report.push_str(&format!(
            "  Steep:  {} cells ({:.1}%)\n",
            self.stats.steep_cells, steep_pct
        ));

        report
    }
}

/// Example of how this mod would be integrated into SC4's callback system
///
/// This function would be called from your cRZCOMDllDirector implementation
pub fn on_city_loaded(city: &City) -> Result<(), Box<dyn Error>> {
    // Create mod instance (in real code, you'd store this somewhere)
    let mut terrain_mod = TerrainAnalyzerMod::new();

    // Initialize with city data
    terrain_mod.initialize(city)?;

    // Generate and display report
    let report = terrain_mod.generate_report();
    println!("{}", report);

    // Find good building locations
    let flat_areas = terrain_mod.find_flat_areas(5);
    println!("Found {} suitable flat areas for building", flat_areas.len());

    // Example: Get terrain at specific location
    let test_coord = CellCoord { x: 32, z: 32 };
    if let Some(terrain) = terrain_mod.get_terrain(test_coord) {
        println!(
            "\nTerrain at ({}, {}): elevation {:.2}, slope {:.2}°, category: {:?}",
            test_coord.x, test_coord.z, terrain.elevation, terrain.slope, terrain.category
        );
    }

    Ok(())
}

fn main() {
    println!("Terrain Analyzer Mod - Example Implementation\n");
    println!("This demonstrates a realistic mod using the safe Rust API.");
    println!("Notice: The entire mod is written in safe Rust with no unsafe blocks!\n");
    println!("In a real mod, this would be called from your director's hooks.");
}
