//! SimCity 4 Rust Bindings - Proof of Concept
//!
//! This library demonstrates how to create safe Rust bindings for SimCity 4's GZCOM DLL interface.
//!
//! # Architecture
//!
//! The bindings are structured in three layers:
//!
//! 1. **FFI Layer** (`*_ffi` modules): Unsafe, low-level bindings that mirror C++ vtables exactly
//! 2. **Safe Wrapper Layer** (`*_safe` modules): Safe Rust APIs that encapsulate all unsafe code
//! 3. **User Code**: Your mod logic, written in 100% safe Rust
//!
//! # Example Usage
//!
//! ```no_run
//! use sc4_rust_poc::city_safe::{City, WorldPosition, CellCoord};
//!
//! fn my_mod_logic(city: &City) -> Result<(), Box<dyn std::error::Error>> {
//!     // All of this is safe Rust!
//!     println!("City serial: {}", city.serial_number());
//!
//!     let size = city.size();
//!     println!("City dimensions: {}x{}", size.width, size.height);
//!
//!     let cells = city.cell_count();
//!     println!("Grid size: {}x{} cells", cells.x, cells.z);
//!
//!     // Coordinate conversions
//!     let world_pos = WorldPosition { x: 100.0, z: 200.0 };
//!     if city.is_position_in_bounds(world_pos) {
//!         let cell = city.position_to_cell(world_pos)?;
//!         println!("Position ({}, {}) is in cell ({}, {})",
//!                  world_pos.x, world_pos.z, cell.x, cell.z);
//!     }
//!
//!     Ok(())
//! }
//! ```
//!
//! # Safety
//!
//! The safe wrapper layer guarantees:
//! - No unsafe code in user-facing APIs
//! - Proper lifetime management to prevent use-after-free
//! - Type-safe conversions between Rust and C++ types
//! - Idiomatic error handling with `Result<T, E>`
//!
//! The only `unsafe` code is in the FFI bindings, which are thoroughly documented.

pub mod base_types;
pub mod string_types;
pub mod string_types_v2;
pub mod city_ffi;
pub mod city_safe;
pub mod director_ffi;
pub mod director;

// Re-export commonly used types for convenience
pub use city_safe::{City, CityError, WorldPosition, CellCoord, CitySize, DifficultyLevel};
pub use base_types::{IGZUnknown, GZIID_IGZUNKNOWN};
pub use city_ffi::{ISC4City, GZIID_ISC4CITY};
pub use director::{DirectorHooks, RustDirector};
pub use director_ffi::{IGZCOMDirector, IGZCOM, IGZFrameWork};

use std::ffi::c_void;

/// Entry point function that SimCity 4 calls to get the COM Director
///
/// This is the main hook into the game. When SC4 loads your DLL, it calls this function
/// to get an instance of your director, which implements the game's plugin interface.
///
/// # Example Implementation
///
/// ```no_run
/// use sc4_rust_poc::*;
/// use std::ffi::c_void;
///
/// #[no_mangle]
/// pub extern "C" fn GZDllGetGZCOMDirector() -> *mut c_void {
///     // In a real implementation, you'd return your director instance
///     // For now, this is just a placeholder
///     std::ptr::null_mut()
/// }
/// ```
#[no_mangle]
pub extern "C" fn GZDllGetGZCOMDirector() -> *mut c_void {
    // This is where you'd return your cRZCOMDllDirector implementation
    // For this PoC, we return null since we don't have a full director yet
    std::ptr::null_mut()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_types_compile() {
        // This test just ensures all the types are properly defined
        // We can't actually test functionality without the game running

        let world_pos = WorldPosition { x: 100.0, z: 200.0 };
        assert_eq!(world_pos.x, 100.0);
        assert_eq!(world_pos.z, 200.0);

        let cell = CellCoord { x: 10, z: 20 };
        assert_eq!(cell.x, 10);
        assert_eq!(cell.z, 20);
    }

    #[test]
    fn test_difficulty_level_conversion() {
        assert_eq!(DifficultyLevel::from(0), DifficultyLevel::Easy);
        assert_eq!(DifficultyLevel::from(1), DifficultyLevel::Medium);
        assert_eq!(DifficultyLevel::from(2), DifficultyLevel::Hard);
        assert_eq!(DifficultyLevel::from(999), DifficultyLevel::Medium); // Unknown defaults to medium
    }
}
