//! FFI bindings for cIGZCOMDirector and related types
//!
//! This module provides the low-level bindings needed to implement a
//! SimCity 4 DLL director in Rust.

use crate::base_types::{IGZUnknown, IGZUnknownVTable};
use crate::string_types_v2::IGZString;
use std::ffi::c_void;

/// Interface IID for cIGZCOMDirector
pub const GZIID_IGZCOMDIRECTOR: u32 = 0xA21EE941;

/// Interface IID for cIGZFrameWorkHooks
pub const GZIID_IGZFRAMEWORKHOOKS: u32 = 0x03FA40BF;

// Forward declarations
#[repr(C)]
pub struct IGZCOM {
    _opaque: [u8; 0],
}

#[repr(C)]
pub struct IGZFrameWork {
    _opaque: [u8; 0],
}

/// Callback function for enumerating class objects
pub type ClassObjectEnumerationCallback =
    unsafe extern "C" fn(clsid: u32, reserved: u32, context: *mut c_void);

/// cIGZCOMDirector interface
///
/// The director is the main entry point for a DLL plugin.
#[repr(C)]
pub struct IGZCOMDirector {
    pub vtable: *const IGZCOMDirectorVTable,
}

#[repr(C)]
pub struct IGZCOMDirectorVTable {
    // =========================================================================
    // Inherited from cIGZUnknown
    // =========================================================================
    pub base: IGZUnknownVTable,

    // =========================================================================
    // cIGZCOMDirector methods
    // =========================================================================

    /// bool InitializeCOM(cIGZCOM* pCOM, const cIGZString& sLibraryPath)
    pub initialize_com: unsafe extern "thiscall" fn(
        this: *mut IGZCOMDirector,
        com: *mut IGZCOM,
        library_path: *const IGZString,
    ) -> bool,

    /// bool OnStart(cIGZCOM* pCOM)
    pub on_start: unsafe extern "thiscall" fn(this: *mut IGZCOMDirector, com: *mut IGZCOM) -> bool,

    /// void EnumClassObjects(ClassObjectEnumerationCallback pCallback, void* pContext)
    pub enum_class_objects: unsafe extern "thiscall" fn(
        this: *mut IGZCOMDirector,
        callback: ClassObjectEnumerationCallback,
        context: *mut c_void,
    ),

    /// bool GetClassObject(uint32_t rclsid, uint32_t riid, void** ppvObj)
    pub get_class_object: unsafe extern "thiscall" fn(
        this: *mut IGZCOMDirector,
        clsid: u32,
        iid: u32,
        ppv_obj: *mut *mut c_void,
    ) -> bool,

    /// bool CanUnloadNow(void)
    pub can_unload_now: unsafe extern "thiscall" fn(this: *mut IGZCOMDirector) -> bool,

    /// bool OnUnload(void)
    pub on_unload: unsafe extern "thiscall" fn(this: *mut IGZCOMDirector) -> bool,

    /// uint32_t RefCount(void)
    pub ref_count: unsafe extern "thiscall" fn(this: *mut IGZCOMDirector) -> u32,

    /// uint32_t RemoveRef(void)
    pub remove_ref: unsafe extern "thiscall" fn(this: *mut IGZCOMDirector) -> u32,

    /// cIGZFrameWork* FrameWork(void)
    pub framework: unsafe extern "thiscall" fn(this: *mut IGZCOMDirector) -> *mut IGZFrameWork,

    /// cIGZCOM* GZCOM(void)
    pub gzcom: unsafe extern "thiscall" fn(this: *mut IGZCOMDirector) -> *mut IGZCOM,

    /// void AddDirector(cIGZCOMDirector* pCOMDirector)
    pub add_director:
        unsafe extern "thiscall" fn(this: *mut IGZCOMDirector, director: *mut IGZCOMDirector),

    /// bool GetLibraryPath(cIGZString& sLibraryPath)
    pub get_library_path: unsafe extern "thiscall" fn(
        this: *mut IGZCOMDirector,
        library_path: *mut IGZString,
    ) -> bool,

    /// uint32_t GetHeapAllocatedSize(void)
    pub get_heap_allocated_size: unsafe extern "thiscall" fn(this: *mut IGZCOMDirector) -> u32,
}

/// cIGZFrameWorkHooks interface
///
/// This interface provides callbacks for various stages of the game lifecycle.
#[repr(C)]
pub struct IGZFrameWorkHooks {
    pub vtable: *const IGZFrameWorkHooksVTable,
}

#[repr(C)]
pub struct IGZFrameWorkHooksVTable {
    // =========================================================================
    // Inherited from cIGZUnknown
    // =========================================================================
    pub base: IGZUnknownVTable,

    // =========================================================================
    // cIGZFrameWorkHooks methods
    // =========================================================================

    /// bool PreFrameWorkInit(void)
    pub pre_framework_init: unsafe extern "thiscall" fn(this: *mut IGZFrameWorkHooks) -> bool,

    /// bool PreAppInit(void)
    pub pre_app_init: unsafe extern "thiscall" fn(this: *mut IGZFrameWorkHooks) -> bool,

    /// bool PostAppInit(void)
    pub post_app_init: unsafe extern "thiscall" fn(this: *mut IGZFrameWorkHooks) -> bool,

    /// bool PreAppShutdown(void)
    pub pre_app_shutdown: unsafe extern "thiscall" fn(this: *mut IGZFrameWorkHooks) -> bool,

    /// bool PostAppShutdown(void)
    pub post_app_shutdown: unsafe extern "thiscall" fn(this: *mut IGZFrameWorkHooks) -> bool,

    /// bool PostSystemServiceShutdown(void)
    pub post_system_service_shutdown:
        unsafe extern "thiscall" fn(this: *mut IGZFrameWorkHooks) -> bool,

    /// bool AbortiveQuit(void)
    pub abortive_quit: unsafe extern "thiscall" fn(this: *mut IGZFrameWorkHooks) -> bool,

    /// bool OnInstall(void)
    pub on_install: unsafe extern "thiscall" fn(this: *mut IGZFrameWorkHooks) -> bool,
}
