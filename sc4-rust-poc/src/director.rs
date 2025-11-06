//! Safe Rust director implementation
//!
//! This module provides a safe way to implement a SimCity 4 DLL director in Rust.

use crate::base_types::{IGZUnknown, IGZUnknownVTable, GZIID_IGZUNKNOWN};
use crate::director_ffi::*;
use crate::string_types_v2::IGZString;
use std::collections::HashMap;
use std::ffi::c_void;
use std::ptr;

/// Trait that user code implements to provide custom director behavior
///
/// All methods have default implementations that return true, so you only
/// need to override the hooks you care about.
pub trait DirectorHooks {
    /// Called before the framework is initialized
    fn pre_framework_init(&mut self) -> bool {
        true
    }

    /// Called before the app is initialized
    fn pre_app_init(&mut self) -> bool {
        true
    }

    /// Called after the app is initialized
    ///
    /// This is typically where you'd set up your mod.
    fn post_app_init(&mut self) -> bool {
        true
    }

    /// Called before the app shuts down
    fn pre_app_shutdown(&mut self) -> bool {
        true
    }

    /// Called after the app shuts down
    fn post_app_shutdown(&mut self) -> bool {
        true
    }

    /// Called after system services shut down
    fn post_system_service_shutdown(&mut self) -> bool {
        true
    }

    /// Called on abortive quit (crash)
    fn abortive_quit(&mut self) -> bool {
        true
    }

    /// Called on install
    fn on_install(&mut self) -> bool {
        true
    }

    /// Return the director ID (must be unique)
    fn get_director_id(&self) -> u32;

    /// Called when the director is loaded
    fn on_start(&mut self, _com: *mut IGZCOM) -> bool {
        true
    }
}

/// Factory function type for creating class objects
pub type FactoryFunction = unsafe extern "C" fn(iid: u32, ppv_obj: *mut *mut c_void) -> bool;

/// Rust implementation of cRZCOMDllDirector
///
/// This struct must have a specific memory layout to be compatible with C++.
#[repr(C)]
pub struct RustDirector<T: DirectorHooks> {
    /// Vtable for cIGZCOMDirector interface (primary)
    vtable_director: *const IGZCOMDirectorVTable,

    /// Reference count
    ref_count: u32,

    /// Director ID
    director_id: u32,

    /// COM instance
    com: *mut IGZCOM,

    /// Framework instance
    framework: *mut IGZFrameWork,

    /// Class factory map
    class_factories: HashMap<u32, FactoryFunction>,

    /// User hooks implementation
    hooks: T,
}

impl<T: DirectorHooks> RustDirector<T> {
    /// Create a new director with user-provided hooks
    ///
    /// # Safety
    /// This creates a director that will be called by the game.
    /// The director must remain valid for the entire game lifetime.
    pub unsafe fn new(hooks: T) -> Box<Self> {
        let director_id = hooks.get_director_id();

        Box::new(RustDirector {
            vtable_director: &DIRECTOR_VTABLE as *const _,
            ref_count: 0,
            director_id,
            com: ptr::null_mut(),
            framework: ptr::null_mut(),
            class_factories: HashMap::new(),
            hooks,
        })
    }

    /// Get a pointer to this director as an IGZCOMDirector
    pub fn as_director_ptr(&mut self) -> *mut IGZCOMDirector {
        self as *mut _ as *mut IGZCOMDirector
    }

    /// Register a class factory
    pub fn add_class(&mut self, clsid: u32, factory: FactoryFunction) {
        self.class_factories.insert(clsid, factory);
    }
}

// =============================================================================
// Vtable implementations
// =============================================================================

/// The director vtable (for cIGZCOMDirector interface)
static DIRECTOR_VTABLE: IGZCOMDirectorVTable = IGZCOMDirectorVTable {
    base: IGZUnknownVTable {
        query_interface: director_query_interface,
        add_ref: director_add_ref,
        release: director_release,
    },
    initialize_com: director_initialize_com,
    on_start: director_on_start,
    enum_class_objects: director_enum_class_objects,
    get_class_object: director_get_class_object,
    can_unload_now: director_can_unload_now,
    on_unload: director_on_unload,
    ref_count: director_ref_count,
    remove_ref: director_remove_ref,
    framework: director_framework,
    gzcom: director_gzcom,
    add_director: director_add_director,
    get_library_path: director_get_library_path,
    get_heap_allocated_size: director_get_heap_allocated_size,
};

/// The hooks vtable (for cIGZFrameWorkHooks interface)
static HOOKS_VTABLE: IGZFrameWorkHooksVTable = IGZFrameWorkHooksVTable {
    base: IGZUnknownVTable {
        query_interface: hooks_query_interface,
        add_ref: hooks_add_ref,
        release: hooks_release,
    },
    pre_framework_init: hooks_pre_framework_init,
    pre_app_init: hooks_pre_app_init,
    post_app_init: hooks_post_app_init,
    pre_app_shutdown: hooks_pre_app_shutdown,
    post_app_shutdown: hooks_post_app_shutdown,
    post_system_service_shutdown: hooks_post_system_service_shutdown,
    abortive_quit: hooks_abortive_quit,
    on_install: hooks_on_install,
};

// =============================================================================
// Helper macro to get director from different interface pointers
// =============================================================================

macro_rules! get_director {
    ($ptr:expr, $T:ty) => {{
        $ptr as *mut RustDirector<$T>
    }};
}

// =============================================================================
// Director interface implementations
// =============================================================================

unsafe extern "thiscall" fn director_query_interface<T: DirectorHooks>(
    this: *mut IGZUnknown,
    riid: u32,
    ppv_obj: *mut *mut c_void,
) -> bool {
    if ppv_obj.is_null() {
        return false;
    }

    match riid {
        GZIID_IGZCOMDIRECTOR => {
            *ppv_obj = this as *mut c_void;
            (DIRECTOR_VTABLE.base.add_ref)(this);
            true
        }
        GZIID_IGZFRAMEWORKHOOKS => {
            // Return pointer to hooks interface
            // The vtable pointer needs to be swapped
            let director = get_director!(this, T);
            *ppv_obj = director as *mut c_void;
            (DIRECTOR_VTABLE.base.add_ref)(this);
            true
        }
        GZIID_IGZUNKNOWN => {
            *ppv_obj = this as *mut c_void;
            (DIRECTOR_VTABLE.base.add_ref)(this);
            true
        }
        _ => false,
    }
}

unsafe extern "thiscall" fn director_add_ref<T: DirectorHooks>(this: *mut IGZUnknown) -> u32 {
    let director = get_director!(this, T);
    (*director).ref_count += 1;
    (*director).ref_count
}

unsafe extern "thiscall" fn director_release<T: DirectorHooks>(this: *mut IGZUnknown) -> u32 {
    let director = get_director!(this, T);
    if (*director).ref_count > 0 {
        (*director).ref_count -= 1;
    }
    (*director).ref_count
}

unsafe extern "thiscall" fn director_initialize_com<T: DirectorHooks>(
    this: *mut IGZCOMDirector,
    com: *mut IGZCOM,
    _library_path: *const IGZString,
) -> bool {
    if com.is_null() {
        return false;
    }

    let director = get_director!(this, T);
    (*director).com = com;

    // Get framework from COM
    // (*director).framework = (*com).vtable.framework(com);
    // Note: We'd need to bind cIGZCOM vtable to actually call this

    true
}

unsafe extern "thiscall" fn director_on_start<T: DirectorHooks>(
    this: *mut IGZCOMDirector,
    com: *mut IGZCOM,
) -> bool {
    let director = get_director!(this, T);
    (*director).hooks.on_start(com)
}

unsafe extern "thiscall" fn director_enum_class_objects<T: DirectorHooks>(
    this: *mut IGZCOMDirector,
    callback: ClassObjectEnumerationCallback,
    context: *mut c_void,
) {
    let director = get_director!(this, T);
    for (clsid, _) in &(*director).class_factories {
        callback(*clsid, 0, context);
    }
}

unsafe extern "thiscall" fn director_get_class_object<T: DirectorHooks>(
    this: *mut IGZCOMDirector,
    clsid: u32,
    iid: u32,
    ppv_obj: *mut *mut c_void,
) -> bool {
    let director = get_director!(this, T);

    if let Some(factory) = (*director).class_factories.get(&clsid) {
        factory(iid, ppv_obj)
    } else {
        false
    }
}

unsafe extern "thiscall" fn director_can_unload_now<T: DirectorHooks>(
    this: *mut IGZCOMDirector,
) -> bool {
    let director = get_director!(this, T);
    (*director).ref_count == 0
}

unsafe extern "thiscall" fn director_on_unload<T: DirectorHooks>(
    _this: *mut IGZCOMDirector,
) -> bool {
    true
}

unsafe extern "thiscall" fn director_ref_count<T: DirectorHooks>(
    this: *mut IGZCOMDirector,
) -> u32 {
    let director = get_director!(this, T);
    (*director).ref_count
}

unsafe extern "thiscall" fn director_remove_ref<T: DirectorHooks>(
    this: *mut IGZCOMDirector,
) -> u32 {
    director_release::<T>(this as *mut IGZUnknown)
}

unsafe extern "thiscall" fn director_framework<T: DirectorHooks>(
    this: *mut IGZCOMDirector,
) -> *mut IGZFrameWork {
    let director = get_director!(this, T);
    (*director).framework
}

unsafe extern "thiscall" fn director_gzcom<T: DirectorHooks>(
    this: *mut IGZCOMDirector,
) -> *mut IGZCOM {
    let director = get_director!(this, T);
    (*director).com
}

unsafe extern "thiscall" fn director_add_director<T: DirectorHooks>(
    _this: *mut IGZCOMDirector,
    _director: *mut IGZCOMDirector,
) {
    // Child directors not supported in this simplified version
}

unsafe extern "thiscall" fn director_get_library_path<T: DirectorHooks>(
    _this: *mut IGZCOMDirector,
    _library_path: *mut IGZString,
) -> bool {
    // Would need to store library path
    true
}

unsafe extern "thiscall" fn director_get_heap_allocated_size<T: DirectorHooks>(
    _this: *mut IGZCOMDirector,
) -> u32 {
    0
}

// =============================================================================
// Hooks interface implementations
// =============================================================================

unsafe extern "thiscall" fn hooks_query_interface<T: DirectorHooks>(
    this: *mut IGZUnknown,
    riid: u32,
    ppv_obj: *mut *mut c_void,
) -> bool {
    // Forward to director's QueryInterface
    director_query_interface::<T>(this, riid, ppv_obj)
}

unsafe extern "thiscall" fn hooks_add_ref<T: DirectorHooks>(this: *mut IGZUnknown) -> u32 {
    director_add_ref::<T>(this)
}

unsafe extern "thiscall" fn hooks_release<T: DirectorHooks>(this: *mut IGZUnknown) -> u32 {
    director_release::<T>(this)
}

unsafe extern "thiscall" fn hooks_pre_framework_init<T: DirectorHooks>(
    this: *mut IGZFrameWorkHooks,
) -> bool {
    let director = get_director!(this, T);
    (*director).hooks.pre_framework_init()
}

unsafe extern "thiscall" fn hooks_pre_app_init<T: DirectorHooks>(
    this: *mut IGZFrameWorkHooks,
) -> bool {
    let director = get_director!(this, T);
    (*director).hooks.pre_app_init()
}

unsafe extern "thiscall" fn hooks_post_app_init<T: DirectorHooks>(
    this: *mut IGZFrameWorkHooks,
) -> bool {
    let director = get_director!(this, T);
    (*director).hooks.post_app_init()
}

unsafe extern "thiscall" fn hooks_pre_app_shutdown<T: DirectorHooks>(
    this: *mut IGZFrameWorkHooks,
) -> bool {
    let director = get_director!(this, T);
    (*director).hooks.pre_app_shutdown()
}

unsafe extern "thiscall" fn hooks_post_app_shutdown<T: DirectorHooks>(
    this: *mut IGZFrameWorkHooks,
) -> bool {
    let director = get_director!(this, T);
    (*director).hooks.post_app_shutdown()
}

unsafe extern "thiscall" fn hooks_post_system_service_shutdown<T: DirectorHooks>(
    this: *mut IGZFrameWorkHooks,
) -> bool {
    let director = get_director!(this, T);
    (*director).hooks.post_system_service_shutdown()
}

unsafe extern "thiscall" fn hooks_abortive_quit<T: DirectorHooks>(
    this: *mut IGZFrameWorkHooks,
) -> bool {
    let director = get_director!(this, T);
    (*director).hooks.abortive_quit()
}

unsafe extern "thiscall" fn hooks_on_install<T: DirectorHooks>(
    this: *mut IGZFrameWorkHooks,
) -> bool {
    let director = get_director!(this, T);
    (*director).hooks.on_install()
}
