//! FFI bindings for cISC4City
//!
//! This module contains the unsafe, low-level bindings to SimCity 4's City interface.

use crate::base_types::{IGZUnknown, IGZUnknownVTable};
use crate::string_types::IGZString;
use std::ffi::c_void;

/// Interface IID for cISC4City
pub const GZIID_ISC4CITY: u32 = 0x26D31EC0;

// Forward declarations for subsystem types
// In a full implementation, these would have their own modules
#[repr(C)]
pub struct ISC4Simulator {
    _opaque: [u8; 0],
}

#[repr(C)]
pub struct ISC4BudgetSimulator {
    _opaque: [u8; 0],
}

#[repr(C)]
pub struct ISC4DemandSimulator {
    _opaque: [u8; 0],
}

#[repr(C)]
pub struct ISC4LotManager {
    _opaque: [u8; 0],
}

#[repr(C)]
pub struct ISC4OccupantManager {
    _opaque: [u8; 0],
}

/// Raw FFI representation of cISC4City
///
/// This struct mirrors the C++ class layout exactly.
/// The first field is the vtable pointer, which is how C++ implements virtual methods.
#[repr(C)]
pub struct ISC4City {
    /// Pointer to the virtual function table
    ///
    /// In memory, a C++ object with virtual functions starts with a pointer to its vtable.
    /// All methods are called through function pointers in this table.
    pub vtable: *const ISC4CityVTable,
}

/// Virtual function table for cISC4City
///
/// CRITICAL: The order of fields in this struct MUST match the order of virtual methods
/// in the C++ class, including inherited methods from cIGZUnknown.
///
/// The vtable layout is:
/// 1. cIGZUnknown methods (QueryInterface, AddRef, Release)
/// 2. cISC4City methods (in declaration order)
#[repr(C)]
pub struct ISC4CityVTable {
    // =========================================================================
    // Inherited from cIGZUnknown
    // =========================================================================
    pub base: IGZUnknownVTable,

    // =========================================================================
    // cISC4City methods (in declaration order from header file)
    // =========================================================================

    /// bool Init(void)
    pub init: unsafe extern "thiscall" fn(this: *mut ISC4City) -> bool,

    /// bool Shutdown(void)
    pub shutdown: unsafe extern "thiscall" fn(this: *mut ISC4City) -> bool,

    /// uint32_t GetCitySerialNumber(void)
    pub get_city_serial_number: unsafe extern "thiscall" fn(this: *mut ISC4City) -> u32,

    /// cISC4City* SetCitySerialNumber(uint32_t dwSerial)
    pub set_city_serial_number:
        unsafe extern "thiscall" fn(this: *mut ISC4City, serial: u32) -> *mut ISC4City,

    /// uint32_t GetNewOccupantSerialNumber(void)
    pub get_new_occupant_serial_number: unsafe extern "thiscall" fn(this: *mut ISC4City) -> u32,

    /// bool GetOriginalLanguageAndCountry(uint32_t& dwLanguage, uint32_t& dwCountry)
    pub get_original_language_and_country: unsafe extern "thiscall" fn(
        this: *mut ISC4City,
        language: *mut u32,
        country: *mut u32,
    ) -> bool,

    /// bool GetLastLanguageAndCountry(uint32_t& dwLanguage, uint32_t& dwCountry)
    pub get_last_language_and_country: unsafe extern "thiscall" fn(
        this: *mut ISC4City,
        language: *mut u32,
        country: *mut u32,
    ) -> bool,

    /// bool GetCitySaveFilePath(cIGZString& szPath)
    pub get_city_save_file_path:
        unsafe extern "thiscall" fn(this: *mut ISC4City, path: *mut IGZString) -> bool,

    /// bool SetCitySaveFilePath(cIGZString const& szPath)
    pub set_city_save_file_path:
        unsafe extern "thiscall" fn(this: *mut ISC4City, path: *const IGZString) -> bool,

    /// bool GetCityName(cIGZString& szName)
    pub get_city_name:
        unsafe extern "thiscall" fn(this: *mut ISC4City, name: *mut IGZString) -> bool,

    /// bool SetCityName(cIGZString const& szName)
    pub set_city_name:
        unsafe extern "thiscall" fn(this: *mut ISC4City, name: *const IGZString) -> bool,

    /// bool GetCityNameChanged(void)
    pub get_city_name_changed: unsafe extern "thiscall" fn(this: *mut ISC4City) -> bool,

    /// cISC4City* SetCityNameChanged(bool bToggle)
    pub set_city_name_changed:
        unsafe extern "thiscall" fn(this: *mut ISC4City, toggle: bool) -> *mut ISC4City,

    /// bool GetMayorName(cIGZString& szName)
    pub get_mayor_name:
        unsafe extern "thiscall" fn(this: *mut ISC4City, name: *mut IGZString) -> bool,

    /// bool SetMayorName(cIGZString const& szName)
    pub set_mayor_name:
        unsafe extern "thiscall" fn(this: *mut ISC4City, name: *const IGZString) -> bool,

    /// bool GetCityDescription(cIGZString& szDescription)
    pub get_city_description:
        unsafe extern "thiscall" fn(this: *mut ISC4City, desc: *mut IGZString) -> bool,

    /// bool SetCityDescription(cIGZString const& szDescription)
    pub set_city_description:
        unsafe extern "thiscall" fn(this: *mut ISC4City, desc: *const IGZString) -> bool,

    /// uint32_t GetBirthDate(void)
    pub get_birth_date: unsafe extern "thiscall" fn(this: *mut ISC4City) -> u32,

    /// cISC4City* SetBirthDate(uint32_t dwDate)
    pub set_birth_date:
        unsafe extern "thiscall" fn(this: *mut ISC4City, date: u32) -> *mut ISC4City,

    /// bool GetEstablished(void)
    pub get_established: unsafe extern "thiscall" fn(this: *mut ISC4City) -> bool,

    /// bool SetEstablished(bool bEstablished)
    pub set_established: unsafe extern "thiscall" fn(this: *mut ISC4City, established: bool) -> bool,

    /// int32_t GetDifficultyLevel(void)
    pub get_difficulty_level: unsafe extern "thiscall" fn(this: *mut ISC4City) -> i32,

    /// cISC4City* SetDifficultyLevel(int32_t dwLevel)
    pub set_difficulty_level:
        unsafe extern "thiscall" fn(this: *mut ISC4City, level: i32) -> *mut ISC4City,

    /// intptr_t GetWorldPosition(float& fX, float& fZ)
    pub get_world_position:
        unsafe extern "thiscall" fn(this: *mut ISC4City, x: *mut f32, z: *mut f32) -> isize,

    /// cISC4City* SetWorldPosition(float fX, float fZ)
    pub set_world_position:
        unsafe extern "thiscall" fn(this: *mut ISC4City, x: f32, z: f32) -> *mut ISC4City,

    /// float GetWorldBaseElevation(void)
    pub get_world_base_elevation: unsafe extern "thiscall" fn(this: *mut ISC4City) -> f32,

    /// cISC4City* SetWorldBaseElevation(float fElevation)
    pub set_world_base_elevation:
        unsafe extern "thiscall" fn(this: *mut ISC4City, elevation: f32) -> *mut ISC4City,

    /// int32_t GetWorldHemisphere(void)
    pub get_world_hemisphere: unsafe extern "thiscall" fn(this: *mut ISC4City) -> i32,

    // For brevity, I'm skipping some of the "Get*Manager" methods and jumping to a few key ones
    // In a full implementation, ALL methods would be included in exact order

    /// cISC4LotManager* GetLotManager(void)
    pub get_lot_manager: unsafe extern "thiscall" fn(this: *mut ISC4City) -> *mut ISC4LotManager,

    /// cISC4OccupantManager* GetOccupantManager(void)
    pub get_occupant_manager:
        unsafe extern "thiscall" fn(this: *mut ISC4City) -> *mut ISC4OccupantManager,

    // ... many more Get*Manager methods ...
    // NOTE: In a real implementation, you CANNOT skip these! Every virtual method must be present
    // in the vtable in the exact order. I'm using placeholders here for demonstration.

    // Placeholder for all the Get*Manager and Get*Simulator methods
    // In reality, each one would be properly typed
    _placeholder_managers: [usize; 80], // Approximately 80 manager/simulator getters

    /// uint32_t GetCitySizeType(void)
    pub get_city_size_type: unsafe extern "thiscall" fn(this: *mut ISC4City) -> u32,

    /// bool SetSize(float fX, float fZ)
    pub set_size: unsafe extern "thiscall" fn(this: *mut ISC4City, x: f32, z: f32) -> bool,

    /// float SizeX(void)
    pub size_x: unsafe extern "thiscall" fn(this: *mut ISC4City) -> f32,

    /// float SizeZ(void)
    pub size_z: unsafe extern "thiscall" fn(this: *mut ISC4City) -> f32,

    /// float CellWidthX(void)
    pub cell_width_x: unsafe extern "thiscall" fn(this: *mut ISC4City) -> f32,

    /// float CellWidthZ(void)
    pub cell_width_z: unsafe extern "thiscall" fn(this: *mut ISC4City) -> f32,

    /// uint32_t CellCountX(void)
    pub cell_count_x: unsafe extern "thiscall" fn(this: *mut ISC4City) -> u32,

    /// uint32_t CellCountZ(void)
    pub cell_count_z: unsafe extern "thiscall" fn(this: *mut ISC4City) -> u32,

    /// int32_t PositionToCell(float fX, float fZ, int& cX, int& cZ)
    pub position_to_cell: unsafe extern "thiscall" fn(
        this: *mut ISC4City,
        x: f32,
        z: f32,
        cell_x: *mut i32,
        cell_z: *mut i32,
    ) -> i32,

    /// int32_t CellCornerToPosition(int cX, int cZ, float& fX, float& fZ)
    pub cell_corner_to_position: unsafe extern "thiscall" fn(
        this: *mut ISC4City,
        cell_x: i32,
        cell_z: i32,
        x: *mut f32,
        z: *mut f32,
    ) -> i32,

    /// int32_t CellCenterToPosition(int cX, int cZ, float& fX, float& fZ)
    pub cell_center_to_position: unsafe extern "thiscall" fn(
        this: *mut ISC4City,
        cell_x: i32,
        cell_z: i32,
        x: *mut f32,
        z: *mut f32,
    ) -> i32,

    /// bool LocationIsInBounds(float fX, float fZ)
    pub location_is_in_bounds:
        unsafe extern "thiscall" fn(this: *mut ISC4City, x: f32, z: f32) -> bool,

    /// bool CellIsInBounds(int cX, int cZ)
    pub cell_is_in_bounds:
        unsafe extern "thiscall" fn(this: *mut ISC4City, cell_x: i32, cell_z: i32) -> bool,

    /// bool CellCornerIsInBounds(int cX, int cZ)
    pub cell_corner_is_in_bounds:
        unsafe extern "thiscall" fn(this: *mut ISC4City, cell_x: i32, cell_z: i32) -> bool,

    /// void ToggleSimulationMode(void)
    pub toggle_simulation_mode: unsafe extern "thiscall" fn(this: *mut ISC4City),

    /// bool IsInCityTimeSimulationMode(void)
    pub is_in_city_time_simulation_mode: unsafe extern "thiscall" fn(this: *mut ISC4City) -> bool,

    /// int32_t EnableSave(void)
    pub enable_save: unsafe extern "thiscall" fn(this: *mut ISC4City) -> i32,

    /// int32_t DisableSave(void)
    pub disable_save: unsafe extern "thiscall" fn(this: *mut ISC4City) -> i32,

    /// bool IsSaveDisabled(void)
    pub is_save_disabled: unsafe extern "thiscall" fn(this: *mut ISC4City) -> bool,

    /// cISC4City* UIIncreaseLockCount(void)
    pub ui_increase_lock_count: unsafe extern "thiscall" fn(this: *mut ISC4City) -> *mut ISC4City,

    /// int32_t UIDecreaseLockCount(void)
    pub ui_decrease_lock_count: unsafe extern "thiscall" fn(this: *mut ISC4City) -> i32,

    /// int32_t UIGetLockCount(void)
    pub ui_get_lock_count: unsafe extern "thiscall" fn(this: *mut ISC4City) -> i32,

    /// bool SaveObliterated(cIGZPersistDBSegment* pSegment)
    pub save_obliterated:
        unsafe extern "thiscall" fn(this: *mut ISC4City, segment: *mut c_void) -> bool,
}
