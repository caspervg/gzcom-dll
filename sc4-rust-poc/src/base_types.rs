//! Base types for GZCOM interface
//!
//! This module provides the fundamental COM-like types used throughout SimCity 4's API.

use std::ffi::c_void;

/// Interface IID for cIGZUnknown
pub const GZIID_IGZUNKNOWN: u32 = 1;

/// The fundamental COM-like object that all game interfaces inherit from.
///
/// In C++:
/// ```cpp
/// class cIGZUnknown {
///     virtual bool QueryInterface(uint32_t riid, void** ppvObj) = 0;
///     virtual uint32_t AddRef(void) = 0;
///     virtual uint32_t Release(void) = 0;
/// };
/// ```
#[repr(C)]
pub struct IGZUnknown {
    pub vtable: *const IGZUnknownVTable,
}

/// Virtual function table for IGZUnknown
///
/// IMPORTANT: The calling convention is `thiscall` for MSVC 32-bit.
/// The `this` pointer is passed in ECX register.
#[repr(C)]
pub struct IGZUnknownVTable {
    /// Casts the object to the interface specified by riid
    ///
    /// # Safety
    /// - `this` must be a valid pointer to an IGZUnknown-derived object
    /// - `ppv_obj` must be a valid pointer to a void pointer
    pub query_interface: unsafe extern "thiscall" fn(
        this: *mut IGZUnknown,
        riid: u32,
        ppv_obj: *mut *mut c_void,
    ) -> bool,

    /// Adds a reference to this object
    ///
    /// # Safety
    /// - `this` must be a valid pointer to an IGZUnknown-derived object
    pub add_ref: unsafe extern "thiscall" fn(this: *mut IGZUnknown) -> u32,

    /// Removes a reference to this object
    ///
    /// # Safety
    /// - `this` must be a valid pointer to an IGZUnknown-derived object
    /// - If ref count reaches 0, the object may be deleted
    pub release: unsafe extern "thiscall" fn(this: *mut IGZUnknown) -> u32,
}

impl IGZUnknown {
    /// Query for a different interface on this object
    ///
    /// # Safety
    /// This is unsafe because:
    /// - We're calling through an FFI boundary
    /// - The vtable pointer must be valid
    /// - The game's memory management must be intact
    pub unsafe fn query_interface<T>(&self, riid: u32) -> Option<*mut T> {
        let mut result: *mut c_void = std::ptr::null_mut();
        let success = ((*self.vtable).query_interface)(
            self as *const _ as *mut _,
            riid,
            &mut result,
        );

        if success && !result.is_null() {
            Some(result as *mut T)
        } else {
            None
        }
    }

    /// Increment the reference count
    ///
    /// # Safety
    /// This is unsafe because we're calling through an FFI boundary
    pub unsafe fn add_ref(&self) -> u32 {
        ((*self.vtable).add_ref)(self as *const _ as *mut _)
    }

    /// Decrement the reference count
    ///
    /// # Safety
    /// This is unsafe because:
    /// - We're calling through an FFI boundary
    /// - The object may be deleted if ref count reaches 0
    pub unsafe fn release(&self) -> u32 {
        ((*self.vtable).release)(self as *const _ as *mut _)
    }
}
