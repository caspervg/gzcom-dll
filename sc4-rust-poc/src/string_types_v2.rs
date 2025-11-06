//! Proper string type bindings for GZCOM
//!
//! Based on cIGZString.h and cRZBaseString implementation.
//! This is a more complete and accurate version.

use crate::base_types::{IGZUnknown, IGZUnknownVTable, GZIID_IGZUNKNOWN};
use std::ffi::{CStr, CString};
use std::fmt;

/// Interface IID for cIGZString
pub const GZIID_IGZSTRING: u32 = 0x089b7dc8;

/// FFI representation of cIGZString interface
///
/// In C++, this is a pure virtual interface with ~22 methods.
#[repr(C)]
pub struct IGZString {
    pub vtable: *const IGZStringVTable,
}

/// Virtual function table for cIGZString
///
/// This follows the exact order from cIGZString.h:
/// 1. cIGZUnknown methods (QueryInterface, AddRef, Release)
/// 2. cIGZString methods (in declaration order)
#[repr(C)]
pub struct IGZStringVTable {
    // =========================================================================
    // Inherited from cIGZUnknown
    // =========================================================================
    pub base: IGZUnknownVTable,

    // =========================================================================
    // cIGZString methods (in exact order from header)
    // =========================================================================

    /// uint32_t FromChar(char const* pszSource)
    pub from_char: unsafe extern "thiscall" fn(this: *mut IGZString, source: *const i8) -> u32,

    /// uint32_t FromChar(char const* pszSource, uint32_t dwLength)
    pub from_char_with_len: unsafe extern "thiscall" fn(
        this: *mut IGZString,
        source: *const i8,
        length: u32,
    ) -> u32,

    /// char const* ToChar(void) const
    pub to_char: unsafe extern "thiscall" fn(this: *const IGZString) -> *const i8,

    /// char const* Data(void) const
    pub data_const: unsafe extern "thiscall" fn(this: *const IGZString) -> *const i8,

    /// uint32_t Strlen(void) const
    pub strlen: unsafe extern "thiscall" fn(this: *const IGZString) -> u32,

    /// bool IsEqual(cIGZString const* szOther, bool bCaseSensitive) const
    pub is_equal_ptr: unsafe extern "thiscall" fn(
        this: *const IGZString,
        other: *const IGZString,
        case_sensitive: bool,
    ) -> bool,

    /// bool IsEqual(cIGZString const& szOther, bool bCaseSensitive) const
    pub is_equal_ref: unsafe extern "thiscall" fn(
        this: *const IGZString,
        other: *const IGZString,
        case_sensitive: bool,
    ) -> bool,

    /// bool IsEqual(char const* pszOther, uint32_t dwLength, bool bCaseSensitive) const
    pub is_equal_str: unsafe extern "thiscall" fn(
        this: *const IGZString,
        other: *const i8,
        length: u32,
        case_sensitive: bool,
    ) -> bool,

    /// int32_t CompareTo(cIGZString const& szOther, bool bCaseSensitive) const
    pub compare_to_ref: unsafe extern "thiscall" fn(
        this: *const IGZString,
        other: *const IGZString,
        case_sensitive: bool,
    ) -> i32,

    /// int32_t CompareTo(char const* pszOther, uint32_t dwLength, bool bCaseSensitive) const
    pub compare_to_str: unsafe extern "thiscall" fn(
        this: *const IGZString,
        other: *const i8,
        length: u32,
        case_sensitive: bool,
    ) -> i32,

    /// cIGZString& operator=(cIGZString const& szOther)
    pub assign: unsafe extern "thiscall" fn(
        this: *mut IGZString,
        other: *const IGZString,
    ) -> *mut IGZString,

    /// int32_t Copy(cIGZString const& szOther)
    pub copy: unsafe extern "thiscall" fn(this: *mut IGZString, other: *const IGZString) -> i32,

    /// int32_t Resize(uint32_t dwNewSize)
    pub resize: unsafe extern "thiscall" fn(this: *mut IGZString, new_size: u32) -> i32,

    /// cIGZString* Append(char const* pszOther, uint32_t dwLength)
    pub append_str: unsafe extern "thiscall" fn(
        this: *mut IGZString,
        other: *const i8,
        length: u32,
    ) -> *mut IGZString,

    /// cIGZString* Append(cIGZString const& szOther)
    pub append_ref: unsafe extern "thiscall" fn(
        this: *mut IGZString,
        other: *const IGZString,
    ) -> *mut IGZString,

    /// cIGZString* Insert(uint32_t dwPos, char const* pszOther, uint32_t dwLength)
    pub insert_str: unsafe extern "thiscall" fn(
        this: *mut IGZString,
        pos: u32,
        other: *const i8,
        length: u32,
    ) -> *mut IGZString,

    /// cIGZString* Insert(uint32_t dwPos, cIGZString const& szOther)
    pub insert_ref: unsafe extern "thiscall" fn(
        this: *mut IGZString,
        pos: u32,
        other: *const IGZString,
    ) -> *mut IGZString,

    /// cIGZString* Replace(uint32_t dwStartPos, char const* pszOther, uint32_t dwLength)
    pub replace_str: unsafe extern "thiscall" fn(
        this: *mut IGZString,
        start: u32,
        other: *const i8,
        length: u32,
    ) -> *mut IGZString,

    /// cIGZString* Replace(uint32_t dwStartPos, cIGZString const& szOther)
    pub replace_ref: unsafe extern "thiscall" fn(
        this: *mut IGZString,
        start: u32,
        other: *const IGZString,
    ) -> *mut IGZString,

    /// cIGZString* Erase(uint32_t dwStartPos, uint32_t dwEndPos)
    pub erase: unsafe extern "thiscall" fn(
        this: *mut IGZString,
        start: u32,
        end: u32,
    ) -> *mut IGZString,

    /// int32_t Find(char const* pszOther, uint32_t dwPos, bool bCaseSensitive) const
    pub find_str: unsafe extern "thiscall" fn(
        this: *const IGZString,
        other: *const i8,
        pos: u32,
        case_sensitive: bool,
    ) -> i32,

    /// int32_t Find(cIGZString const& szOther, uint32_t dwPos, bool bCaseSensitive) const
    pub find_ref: unsafe extern "thiscall" fn(
        this: *const IGZString,
        other: *const IGZString,
        pos: u32,
        case_sensitive: bool,
    ) -> i32,

    /// int32_t RFind(char const* pszOther, uint32_t dwPos, bool bCaseSensitive) const
    pub rfind_str: unsafe extern "thiscall" fn(
        this: *const IGZString,
        other: *const i8,
        pos: u32,
        case_sensitive: bool,
    ) -> i32,

    /// int32_t RFind(cIGZString const& szOther, uint32_t dwPos, bool bCaseSensitive) const
    pub rfind_ref: unsafe extern "thiscall" fn(
        this: *const IGZString,
        other: *const IGZString,
        pos: u32,
        case_sensitive: bool,
    ) -> i32,

    /// cIGZString* Sprintf(char const* pszFormat, ...)
    ///
    /// Note: Variadic functions are tricky in FFI. We'll provide a wrapper that uses
    /// a pre-formatted string instead.
    pub sprintf: unsafe extern "thiscall" fn(this: *mut IGZString, format: *const i8, ...) -> *mut IGZString,
}

impl IGZString {
    /// Convert to Rust String
    ///
    /// # Safety
    /// - The vtable must be valid
    /// - The string data must be valid UTF-8 (or we'll use lossy conversion)
    pub unsafe fn to_string(&self) -> Option<String> {
        let vtable = &*self.vtable;
        let c_str = (vtable.to_char)(self);

        if c_str.is_null() {
            return None;
        }

        let cstr = CStr::from_ptr(c_str);
        Some(cstr.to_string_lossy().into_owned())
    }

    /// Get string length
    ///
    /// # Safety
    /// - The vtable must be valid
    pub unsafe fn len(&self) -> usize {
        let vtable = &*self.vtable;
        (vtable.strlen)(self) as usize
    }

    /// Check if string is empty
    ///
    /// # Safety
    /// - The vtable must be valid
    pub unsafe fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Set string from C string
    ///
    /// # Safety
    /// - The vtable must be valid
    /// - source must be a valid null-terminated C string
    pub unsafe fn from_c_str(&mut self, source: *const i8) -> bool {
        let vtable = &*self.vtable;
        (vtable.from_char)(self, source) != 0
    }

    /// Compare with another string
    ///
    /// # Safety
    /// - The vtable must be valid
    /// - other must be a valid IGZString pointer
    pub unsafe fn compare(&self, other: *const IGZString, case_sensitive: bool) -> i32 {
        let vtable = &*self.vtable;
        (vtable.compare_to_ref)(self, other, case_sensitive)
    }
}

/// Safe wrapper for IGZString with RAII semantics
///
/// This wrapper ensures proper reference counting and provides a safe API.
pub struct GZString {
    ptr: *mut IGZString,
    /// Whether we own this string (and should release it on drop)
    owned: bool,
}

impl GZString {
    /// Wrap an existing IGZString pointer (borrowed, not owned)
    ///
    /// # Safety
    /// - ptr must be a valid IGZString pointer
    /// - The pointer must remain valid for the lifetime of this wrapper
    pub unsafe fn from_ptr(ptr: *mut IGZString) -> Option<Self> {
        if ptr.is_null() {
            None
        } else {
            Some(Self { ptr, owned: false })
        }
    }

    /// Wrap an owned IGZString pointer (will call Release on drop)
    ///
    /// # Safety
    /// - ptr must be a valid IGZString pointer with at least 1 reference
    pub unsafe fn from_owned_ptr(ptr: *mut IGZString) -> Option<Self> {
        if ptr.is_null() {
            None
        } else {
            Some(Self { ptr, owned: true })
        }
    }

    /// Create a new string (requires game's string factory)
    ///
    /// # Safety
    /// This would need to call into the game's GZCOM to create a new cRZBaseString
    pub unsafe fn new(_s: &str) -> Option<Self> {
        // In a real implementation:
        // 1. Call GZCOM()->GetClassObject(CLSID_cRZBaseString, GZIID_cIGZString, &ptr)
        // 2. Call FromChar on the string
        // 3. Return GZString { ptr, owned: true }
        None
    }

    /// Get raw pointer
    pub fn as_ptr(&self) -> *const IGZString {
        self.ptr
    }

    /// Get mutable raw pointer
    pub fn as_mut_ptr(&mut self) -> *mut IGZString {
        self.ptr
    }

    /// Convert to Rust String
    pub fn to_string(&self) -> Option<String> {
        unsafe { (*self.ptr).to_string() }
    }

    /// Get string length
    pub fn len(&self) -> usize {
        unsafe { (*self.ptr).len() }
    }

    /// Check if empty
    pub fn is_empty(&self) -> bool {
        unsafe { (*self.ptr).is_empty() }
    }

    /// Set string content
    pub fn set_str(&mut self, s: &str) -> Result<(), ()> {
        let c_string = CString::new(s).map_err(|_| ())?;
        unsafe {
            if (*self.ptr).from_c_str(c_string.as_ptr()) {
                Ok(())
            } else {
                Err(())
            }
        }
    }

    /// Compare with another string
    pub fn compare(&self, other: &GZString, case_sensitive: bool) -> i32 {
        unsafe { (*self.ptr).compare(other.ptr, case_sensitive) }
    }
}

impl Drop for GZString {
    fn drop(&mut self) {
        if self.owned && !self.ptr.is_null() {
            unsafe {
                // Call Release through the vtable
                let vtable = &*(*self.ptr).vtable;
                (vtable.base.release)(self.ptr as *mut IGZUnknown);
            }
        }
    }
}

impl fmt::Debug for GZString {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self.to_string() {
            Some(s) => write!(f, "GZString({:?})", s),
            None => write!(f, "GZString(<invalid>)"),
        }
    }
}

impl fmt::Display for GZString {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self.to_string() {
            Some(s) => write!(f, "{}", s),
            None => write!(f, "<invalid>"),
        }
    }
}

// Implement PartialEq for easy comparison
impl PartialEq for GZString {
    fn eq(&self, other: &Self) -> bool {
        self.compare(other, true) == 0
    }
}

impl PartialEq<str> for GZString {
    fn eq(&self, other: &str) -> bool {
        match self.to_string() {
            Some(s) => s == other,
            None => false,
        }
    }
}

impl PartialEq<&str> for GZString {
    fn eq(&self, other: &&str) -> bool {
        self == *other
    }
}
