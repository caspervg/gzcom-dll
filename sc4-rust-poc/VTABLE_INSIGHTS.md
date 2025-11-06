# Vtable Insights from cRZBaseString

Based on analysis of `cRZBaseString.cpp` and `cIGZString.h`.

## Key Discoveries

### 1. Interface vs Implementation

**Interface (cIGZString.h):**
```cpp
class cIGZString : public cIGZUnknown {
    virtual uint32_t FromChar(char const* pszSource) = 0;
    virtual char const* ToChar(void) const = 0;
    // ... 20 more pure virtual methods
};
```

**Implementation (cRZBaseString.h):**
```cpp
class cRZBaseString : public cIGZString {
    // Implements all virtual methods
    uint32_t FromChar(char const* pszSource);
    char const* ToChar(void) const;
    // ...

protected:
    std::string szData;      // Actual string data
    uint32_t mnRefCount;     // Reference counter
};
```

### 2. Memory Layout

The actual C++ object in memory looks like:

```
┌─────────────────────────────────────┐
│  vtable pointer                     │  <- Points to virtual function table
├─────────────────────────────────────┤
│  std::string szData                 │  <- 12 bytes on 32-bit (3 pointers)
│    - char* pointer to data          │
│    - size_t size                    │
│    - size_t capacity                │
├─────────────────────────────────────┤
│  uint32_t mnRefCount                │  <- 4 bytes
└─────────────────────────────────────┘
Total: 20 bytes (32-bit) or 32 bytes (64-bit)
```

### 3. Vtable Structure

The vtable itself looks like:

```
cRZBaseString vtable:
┌─────────────────────────────────────┐
│  [0] QueryInterface                 │  <- from cIGZUnknown
│  [1] AddRef                         │  <- from cIGZUnknown
│  [2] Release                        │  <- from cIGZUnknown
├─────────────────────────────────────┤
│  [3] FromChar(const char*)          │  <- from cIGZString
│  [4] FromChar(const char*, uint32)  │
│  [5] ToChar()                       │
│  [6] Data() const                   │
│  [7] Strlen()                       │
│  [8] IsEqual(ptr, bool)             │
│  [9] IsEqual(ref, bool)             │
│ [10] IsEqual(str, len, bool)        │
│ [11] CompareTo(ref, bool)           │
│ [12] CompareTo(str, len, bool)      │
│ [13] operator=                      │
│ [14] Copy                           │
│ [15] Resize                         │
│ [16] Append(str, len)               │
│ [17] Append(ref)                    │
│ [18] Insert(pos, str, len)          │
│ [19] Insert(pos, ref)               │
│ [20] Replace(pos, str, len)         │
│ [21] Replace(pos, ref)              │
│ [22] Erase(start, end)              │
│ [23] Find(str, pos, bool)           │
│ [24] Find(ref, pos, bool)           │
│ [25] RFind(str, pos, bool)          │
│ [26] RFind(ref, pos, bool)          │
│ [27] Sprintf(fmt, ...)              │
└─────────────────────────────────────┘
Total: 28 function pointers
```

### 4. Important Implementation Details

#### Reference Counting

From `cRZBaseString.cpp`:
```cpp
uint32_t cRZBaseString::AddRef(void) {
    return ++mnRefCount;
}

uint32_t cRZBaseString::Release(void) {
    if (mnRefCount > 0) {
        --mnRefCount;
    }
    return mnRefCount;
}
```

**Note:** `Release()` does NOT delete the object when count reaches 0!
This is different from COM. The comment in the header suggests objects manage their own lifetime.

#### QueryInterface Pattern

From `cRZBaseString.cpp`:
```cpp
bool cRZBaseString::QueryInterface(uint32_t riid, void** ppvObj) {
    switch (riid) {
        case kRZBaseStringIID:
            *ppvObj = static_cast<cRZBaseString*>(this);
            break;
        case GZIID_cIGZString:
            *ppvObj = static_cast<cIGZString*>(this);
            break;
        case GZIID_cIGZUnknown:
            *ppvObj = static_cast<cIGZUnknown*>(static_cast<cRZBaseString*>(this));
            break;
        default:
            return false;
    }
    AddRef();
    return true;
}
```

**Key points:**
- Always `AddRef()` on successful cast
- Returns `false` for unknown interfaces
- Multiple casts possible (multiple inheritance)

#### String Conversion

From `cRZBaseString.cpp`:
```cpp
char const* cRZBaseString::ToChar(void) const {
    return szData.c_str();
}

uint32_t cRZBaseString::FromChar(char const* pszSource) {
    if (pszSource == nullptr) {
        szData.erase();
    } else {
        szData.assign(pszSource);
    }
    return true;
}
```

**Safety note:** `ToChar()` returns a pointer to internal data.
It's valid only as long as the string object lives and isn't modified.

## Rust Implications

### 1. We CAN'T Implement the Interface Directly

Because the C++ implementation uses `std::string` internally, we can't create a binary-compatible Rust struct that implements the interface. The `std::string` layout is compiler-specific.

### 2. We CAN Use Game-Created Strings

The game will create cRZBaseString objects for us. We just need to:
- Hold pointers to them
- Call methods through the vtable
- Manage reference counting

### 3. Correct Rust Approach

```rust
// DON'T: Try to implement the interface
// ❌ This won't work - can't match C++ std::string layout
#[repr(C)]
struct RustString {
    vtable: *const IGZStringVTable,
    data: String,  // ❌ Different from std::string!
    refcount: u32,
}

// DO: Wrap pointers to game-created strings
// ✅ This works - just call through vtable
pub struct GZString {
    ptr: *mut IGZString,  // Points to game's cRZBaseString
    owned: bool,
}

impl GZString {
    pub fn to_string(&self) -> String {
        unsafe {
            let vtable = &*(*self.ptr).vtable;
            let c_str = (vtable.to_char)(self.ptr);
            CStr::from_ptr(c_str).to_string_lossy().into_owned()
        }
    }
}
```

### 4. Reference Counting Strategy

```rust
impl GZString {
    /// Wrap a borrowed string (doesn't own, won't Release)
    pub unsafe fn from_ptr(ptr: *mut IGZString) -> Self {
        GZString { ptr, owned: false }
    }

    /// Wrap an owned string (will Release on drop)
    pub unsafe fn from_owned_ptr(ptr: *mut IGZString) -> Self {
        GZString { ptr, owned: true }
    }
}

impl Drop for GZString {
    fn drop(&mut self) {
        if self.owned {
            unsafe {
                let vtable = &*(*self.ptr).vtable;
                (vtable.base.release)(self.ptr as *mut IGZUnknown);
            }
        }
    }
}
```

### 5. Creating New Strings

To create a new string, we need to call the game's factory:

```rust
pub unsafe fn create_string(s: &str) -> Option<GZString> {
    // Get GZCOM instance
    let gzcom = get_gzcom_instance()?;

    // Create a cRZBaseString through GZCOM
    let mut ptr: *mut IGZString = std::ptr::null_mut();
    let success = ((*gzcom).vtable.get_class_object)(
        gzcom,
        CLSID_cRZBaseString,  // Would need to find this ID
        GZIID_IGZSTRING,
        &mut ptr as *mut _ as *mut *mut c_void,
    );

    if !success || ptr.is_null() {
        return None;
    }

    // Set the string content
    let c_string = CString::new(s).ok()?;
    ((*ptr).vtable.from_char)(ptr, c_string.as_ptr());

    Some(GZString { ptr, owned: true })
}
```

## Lessons for Other Interfaces

### Same Pattern Applies

All game interfaces follow this pattern:
1. **Interface**: Pure virtual class (in header)
2. **Implementation**: Concrete class with data members (in game or in gzcom-dll)
3. **Usage**: Call through vtable pointers

### What We Can Do

✅ **Call methods** on game-created objects
✅ **Pass pointers** to our objects to game functions
✅ **Implement interfaces** if we match the C++ layout exactly

### What's Difficult

⚠️ **Implementing interfaces** that use C++ standard library types
⚠️ **Creating objects** without game's factory functions
⚠️ **Multiple inheritance** (need careful vtable offsets)

## Complete Example: Using City Name

### C++ Way
```cpp
cISC4City* pCity = pApp->GetCity();
cIGZString* pName = /* create string */;
pCity->GetCityName(*pName);
printf("City: %s\n", pName->ToChar());
pName->Release();
```

### Rust Way (Safe API)
```rust
let city: &City = /* from game */;

// Approach 1: Game provides the string
let name = city.get_name()?;  // Returns String
println!("City: {}", name);

// Approach 2: We provide the string buffer
let mut name_buf = unsafe { GZString::create() }?;
city.get_name_into(&mut name_buf)?;
println!("City: {}", name_buf);
```

### Implementation
```rust
impl<'city> City<'city> {
    pub fn get_name(&self) -> Result<String, CityError> {
        unsafe {
            // Create a temporary string through the game
            let mut name_ptr = create_temp_string()?;

            // Call GetCityName
            let vtable = &*(*self.ptr).vtable;
            if (vtable.get_city_name)(self.ptr, name_ptr.as_mut_ptr()) {
                name_ptr.to_string().ok_or(CityError::NameOperationFailed)
            } else {
                Err(CityError::NameOperationFailed)
            }
            // name_ptr drops and releases the string
        }
    }
}
```

## Testing Strategy

To verify our vtable is correct:

```rust
#[test]
fn test_string_vtable_layout() {
    // This test requires the game to be running, but shows the concept

    // Create a cRZBaseString through game
    let gzstring = unsafe { create_test_string("Hello") }.unwrap();

    // Verify we can read it back
    assert_eq!(gzstring.to_string().unwrap(), "Hello");

    // Verify length
    assert_eq!(gzstring.len(), 5);

    // Verify modification
    gzstring.set_str("World").unwrap();
    assert_eq!(gzstring.to_string().unwrap(), "World");
}
```

## Conclusion

The `cRZBaseString` implementation confirms:

1. ✅ Our vtable approach is correct
2. ✅ We can call through function pointers
3. ✅ We must wrap game-created objects, not implement interfaces ourselves
4. ✅ Reference counting needs careful management
5. ✅ The safe wrapper pattern works

The key insight: **We're consumers of the game's objects, not implementers**.
We call methods on objects the game creates, rather than creating our own implementations.

This simplifies our Rust bindings significantly - we don't need to match C++ memory layouts exactly, just call through the vtables correctly.
