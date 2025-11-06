//! Safe wrapper API for cISC4City
//!
//! This module provides a safe, idiomatic Rust interface to SimCity 4's City object.
//! All unsafe FFI calls are encapsulated here, and the public API is 100% safe Rust.

use crate::city_ffi::{ISC4City, GZIID_ISC4CITY};
use crate::string_types::{GZString, IGZString};
use std::fmt;

/// Error types for city operations
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CityError {
    /// The city pointer is null or invalid
    InvalidCityPointer,
    /// Failed to get/set city name
    NameOperationFailed,
    /// Failed to get/set mayor name
    MayorNameFailed,
    /// Failed to get/set city description
    DescriptionFailed,
    /// Failed to get/set city path
    PathOperationFailed,
    /// Failed to initialize city
    InitializationFailed,
    /// Position is out of bounds
    OutOfBounds,
    /// Cell coordinates are invalid
    InvalidCell,
    /// Language/country operation failed
    LanguageCountryFailed,
}

impl fmt::Display for CityError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CityError::InvalidCityPointer => write!(f, "Invalid city pointer"),
            CityError::NameOperationFailed => write!(f, "Failed to get/set city name"),
            CityError::MayorNameFailed => write!(f, "Failed to get/set mayor name"),
            CityError::DescriptionFailed => write!(f, "Failed to get/set city description"),
            CityError::PathOperationFailed => write!(f, "Failed to get/set city path"),
            CityError::InitializationFailed => write!(f, "Failed to initialize city"),
            CityError::OutOfBounds => write!(f, "Position is out of bounds"),
            CityError::InvalidCell => write!(f, "Invalid cell coordinates"),
            CityError::LanguageCountryFailed => write!(f, "Language/country operation failed"),
        }
    }
}

impl std::error::Error for CityError {}

pub type Result<T> = std::result::Result<T, CityError>;

/// Cell coordinates in the city grid
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CellCoord {
    pub x: i32,
    pub z: i32,
}

/// World position (floating point coordinates)
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct WorldPosition {
    pub x: f32,
    pub z: f32,
}

/// City size information
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CitySize {
    pub width: f32,
    pub height: f32,
}

/// Cell size information
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CellSize {
    pub width: f32,
    pub height: f32,
}

/// Difficulty levels for the city
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum DifficultyLevel {
    Easy = 0,
    Medium = 1,
    Hard = 2,
}

impl From<i32> for DifficultyLevel {
    fn from(value: i32) -> Self {
        match value {
            0 => DifficultyLevel::Easy,
            1 => DifficultyLevel::Medium,
            2 => DifficultyLevel::Hard,
            _ => DifficultyLevel::Medium, // Default to medium for unknown values
        }
    }
}

/// Safe wrapper around cISC4City
///
/// This struct encapsulates all unsafe interactions with the game's city object.
/// All public methods are safe Rust code.
///
/// # Lifetime
///
/// The lifetime 'city ensures that this wrapper cannot outlive the game object it references.
/// In practice, you typically get a City reference from the game's App object and use it
/// within a callback or frame handler.
///
/// # Example
///
/// ```no_run
/// # use sc4_rust_poc::city_safe::{City, Result};
/// fn process_city(city: &City) -> Result<()> {
///     let name = city.get_name()?;
///     println!("City name: {}", name);
///
///     let size = city.size();
///     println!("City size: {}x{}", size.width, size.height);
///
///     let difficulty = city.difficulty_level()?;
///     println!("Difficulty: {:?}", difficulty);
///
///     Ok(())
/// }
/// ```
pub struct City<'city> {
    ptr: *mut ISC4City,
    _phantom: std::marker::PhantomData<&'city ISC4City>,
}

impl<'city> City<'city> {
    /// Create a City wrapper from a raw pointer
    ///
    /// # Safety
    ///
    /// The caller must ensure that:
    /// - `ptr` is a valid pointer to an ISC4City object
    /// - The object remains valid for the lifetime 'city
    /// - No other code is mutating the object in an unsafe way
    pub unsafe fn from_raw(ptr: *mut ISC4City) -> Option<Self> {
        if ptr.is_null() {
            None
        } else {
            Some(City {
                ptr,
                _phantom: std::marker::PhantomData,
            })
        }
    }

    /// Get the raw pointer (for advanced use cases)
    pub fn as_ptr(&self) -> *mut ISC4City {
        self.ptr
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    /// Initialize the city
    pub fn init(&self) -> Result<()> {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            if (vtable.init)(self.ptr) {
                Ok(())
            } else {
                Err(CityError::InitializationFailed)
            }
        }
    }

    /// Shutdown the city
    pub fn shutdown(&self) -> Result<()> {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            if (vtable.shutdown)(self.ptr) {
                Ok(())
            } else {
                Err(CityError::InitializationFailed)
            }
        }
    }

    // =========================================================================
    // Basic Properties
    // =========================================================================

    /// Get the city's unique serial number
    pub fn serial_number(&self) -> u32 {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.get_city_serial_number)(self.ptr)
        }
    }

    /// Set the city's serial number
    pub fn set_serial_number(&self, serial: u32) {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.set_city_serial_number)(self.ptr, serial);
        }
    }

    /// Get a new unique occupant serial number
    pub fn new_occupant_serial_number(&self) -> u32 {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.get_new_occupant_serial_number)(self.ptr)
        }
    }

    /// Get the city's birth date (as a game date number)
    pub fn birth_date(&self) -> u32 {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.get_birth_date)(self.ptr)
        }
    }

    /// Set the city's birth date
    pub fn set_birth_date(&self, date: u32) {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.set_birth_date)(self.ptr, date);
        }
    }

    /// Check if the city is established
    pub fn is_established(&self) -> bool {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.get_established)(self.ptr)
        }
    }

    /// Set whether the city is established
    pub fn set_established(&self, established: bool) -> Result<()> {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            if (vtable.set_established)(self.ptr, established) {
                Ok(())
            } else {
                Err(CityError::InitializationFailed)
            }
        }
    }

    /// Get the difficulty level
    pub fn difficulty_level(&self) -> Result<DifficultyLevel> {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            let level = (vtable.get_difficulty_level)(self.ptr);
            Ok(DifficultyLevel::from(level))
        }
    }

    /// Set the difficulty level
    pub fn set_difficulty_level(&self, level: DifficultyLevel) {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.set_difficulty_level)(self.ptr, level as i32);
        }
    }

    // =========================================================================
    // Names and Descriptions
    // =========================================================================

    /// Get the city name
    ///
    /// Note: This is a placeholder implementation. In a real implementation,
    /// you'd need to properly handle cIGZString.
    pub fn get_name(&self) -> Result<String> {
        // In reality, you'd:
        // 1. Create a temporary cIGZString
        // 2. Call get_city_name with it
        // 3. Convert to Rust String
        // 4. Clean up the temporary string
        //
        // For this PoC, we'll return a placeholder
        Ok("City Name (placeholder)".to_string())
    }

    /// Set the city name
    pub fn set_name(&self, _name: &str) -> Result<()> {
        // In reality, you'd:
        // 1. Convert Rust &str to cIGZString
        // 2. Call set_city_name
        // 3. Handle the result
        Ok(())
    }

    /// Get the mayor name
    pub fn get_mayor_name(&self) -> Result<String> {
        Ok("Mayor Name (placeholder)".to_string())
    }

    /// Set the mayor name
    pub fn set_mayor_name(&self, _name: &str) -> Result<()> {
        Ok(())
    }

    /// Get the city description
    pub fn get_description(&self) -> Result<String> {
        Ok("City Description (placeholder)".to_string())
    }

    /// Set the city description
    pub fn set_description(&self, _desc: &str) -> Result<()> {
        Ok(())
    }

    // =========================================================================
    // Size and Position
    // =========================================================================

    /// Get the city size
    pub fn size(&self) -> CitySize {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            let width = (vtable.size_x)(self.ptr);
            let height = (vtable.size_z)(self.ptr);
            CitySize { width, height }
        }
    }

    /// Set the city size
    pub fn set_size(&self, width: f32, height: f32) -> Result<()> {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            if (vtable.set_size)(self.ptr, width, height) {
                Ok(())
            } else {
                Err(CityError::OutOfBounds)
            }
        }
    }

    /// Get the cell size
    pub fn cell_size(&self) -> CellSize {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            let width = (vtable.cell_width_x)(self.ptr);
            let height = (vtable.cell_width_z)(self.ptr);
            CellSize { width, height }
        }
    }

    /// Get the number of cells
    pub fn cell_count(&self) -> CellCoord {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            let x = (vtable.cell_count_x)(self.ptr) as i32;
            let z = (vtable.cell_count_z)(self.ptr) as i32;
            CellCoord { x, z }
        }
    }

    /// Get world position
    pub fn world_position(&self) -> WorldPosition {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            let mut x: f32 = 0.0;
            let mut z: f32 = 0.0;
            (vtable.get_world_position)(self.ptr, &mut x, &mut z);
            WorldPosition { x, z }
        }
    }

    /// Set world position
    pub fn set_world_position(&self, pos: WorldPosition) {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.set_world_position)(self.ptr, pos.x, pos.z);
        }
    }

    /// Get base elevation
    pub fn base_elevation(&self) -> f32 {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.get_world_base_elevation)(self.ptr)
        }
    }

    /// Set base elevation
    pub fn set_base_elevation(&self, elevation: f32) {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.set_world_base_elevation)(self.ptr, elevation);
        }
    }

    /// Get the hemisphere (0 = North, 1 = South)
    pub fn hemisphere(&self) -> i32 {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.get_world_hemisphere)(self.ptr)
        }
    }

    // =========================================================================
    // Coordinate Conversion
    // =========================================================================

    /// Convert world position to cell coordinates
    pub fn position_to_cell(&self, pos: WorldPosition) -> Result<CellCoord> {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            let mut cell_x: i32 = 0;
            let mut cell_z: i32 = 0;
            let result = (vtable.position_to_cell)(self.ptr, pos.x, pos.z, &mut cell_x, &mut cell_z);
            if result == 0 {
                Ok(CellCoord { x: cell_x, z: cell_z })
            } else {
                Err(CityError::InvalidCell)
            }
        }
    }

    /// Convert cell corner to world position
    pub fn cell_corner_to_position(&self, cell: CellCoord) -> Result<WorldPosition> {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            let mut x: f32 = 0.0;
            let mut z: f32 = 0.0;
            let result = (vtable.cell_corner_to_position)(self.ptr, cell.x, cell.z, &mut x, &mut z);
            if result == 0 {
                Ok(WorldPosition { x, z })
            } else {
                Err(CityError::InvalidCell)
            }
        }
    }

    /// Convert cell center to world position
    pub fn cell_center_to_position(&self, cell: CellCoord) -> Result<WorldPosition> {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            let mut x: f32 = 0.0;
            let mut z: f32 = 0.0;
            let result = (vtable.cell_center_to_position)(self.ptr, cell.x, cell.z, &mut x, &mut z);
            if result == 0 {
                Ok(WorldPosition { x, z })
            } else {
                Err(CityError::InvalidCell)
            }
        }
    }

    /// Check if a world position is within city bounds
    pub fn is_position_in_bounds(&self, pos: WorldPosition) -> bool {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.location_is_in_bounds)(self.ptr, pos.x, pos.z)
        }
    }

    /// Check if a cell is within city bounds
    pub fn is_cell_in_bounds(&self, cell: CellCoord) -> bool {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.cell_is_in_bounds)(self.ptr, cell.x, cell.z)
        }
    }

    /// Check if a cell corner is within city bounds
    pub fn is_cell_corner_in_bounds(&self, cell: CellCoord) -> bool {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.cell_corner_is_in_bounds)(self.ptr, cell.x, cell.z)
        }
    }

    // =========================================================================
    // Simulation Control
    // =========================================================================

    /// Toggle simulation mode
    pub fn toggle_simulation_mode(&self) {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.toggle_simulation_mode)(self.ptr);
        }
    }

    /// Check if in city time simulation mode
    pub fn is_in_simulation_mode(&self) -> bool {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.is_in_city_time_simulation_mode)(self.ptr)
        }
    }

    /// Enable saving
    pub fn enable_save(&self) -> i32 {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.enable_save)(self.ptr)
        }
    }

    /// Disable saving
    pub fn disable_save(&self) -> i32 {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.disable_save)(self.ptr)
        }
    }

    /// Check if saving is disabled
    pub fn is_save_disabled(&self) -> bool {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.is_save_disabled)(self.ptr)
        }
    }

    // =========================================================================
    // UI Lock Management
    // =========================================================================

    /// Increase UI lock count
    pub fn ui_lock(&self) {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.ui_increase_lock_count)(self.ptr);
        }
    }

    /// Decrease UI lock count
    pub fn ui_unlock(&self) -> i32 {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.ui_decrease_lock_count)(self.ptr)
        }
    }

    /// Get current UI lock count
    pub fn ui_lock_count(&self) -> i32 {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            (vtable.ui_get_lock_count)(self.ptr)
        }
    }
}

// Implement Debug for better development experience
impl<'city> fmt::Debug for City<'city> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("City")
            .field("serial_number", &self.serial_number())
            .field("size", &self.size())
            .field("cell_count", &self.cell_count())
            .field("is_established", &self.is_established())
            .field("is_in_simulation_mode", &self.is_in_simulation_mode())
            .finish()
    }
}
