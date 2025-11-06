# Rust Director Implementation Guide

This guide explains how the Rust director implementation works and how to use it to create SimCity 4 mods.

## Overview

The director is the main entry point for a SimCity 4 DLL plugin. When the game loads your DLL, it calls `GZDllGetGZCOMDirector()` to get an instance of your director.

## Architecture

```
┌────────────────────────────────────────┐
│  GZDllGetGZCOMDirector()              │  <- Entry point (extern "C")
│  Returns: *mut IGZCOMDirector         │
└────────────────┬───────────────────────┘
                 │
┌────────────────▼───────────────────────┐
│  RustDirector<YourHooks>              │  <- Generic director impl
│  - Vtable for IGZCOMDirector          │
│  - Vtable for IGZFrameWorkHooks       │
│  - User hooks implementation           │
└────────────────┬───────────────────────┘
                 │
┌────────────────▼───────────────────────┐
│  YourHooks: DirectorHooks trait       │  <- Your mod logic
│  - post_app_init()                     │
│  - pre_app_shutdown()                  │
│  - etc.                                │
└────────────────────────────────────────┘
```

## Creating a Mod

### Step 1: Implement DirectorHooks

```rust
use sc4_rust_poc::{DirectorHooks, IGZCOM};

pub struct MyMod {
    name: String,
    // Your mod state goes here
}

impl DirectorHooks for MyMod {
    fn get_director_id(&self) -> u32 {
        0x12345678  // Your unique ID
    }

    fn post_app_init(&mut self) -> bool {
        println!("Mod initialized!");
        // Your setup logic here
        true
    }

    fn pre_app_shutdown(&mut self) -> bool {
        println!("Mod shutting down!");
        // Your cleanup logic here
        true
    }

    // Other hooks have default implementations
}
```

### Step 2: Create Global Director

```rust
use sc4_rust_poc::RustDirector;
use std::sync::Mutex;

static DIRECTOR: Mutex<Option<Box<RustDirector<MyMod>>>> = Mutex::new(None);
```

### Step 3: Implement GZDllGetGZCOMDirector

```rust
use std::ffi::c_void;

#[no_mangle]
pub extern "C" fn GZDllGetGZCOMDirector() -> *mut c_void {
    unsafe {
        let mut guard = DIRECTOR.lock().unwrap();

        if guard.is_none() {
            let hooks = MyMod { name: "My Mod".to_string() };
            let mut director = RustDirector::new(hooks);
            let ptr = director.as_director_ptr();
            *guard = Some(director);
            return ptr as *mut c_void;
        }

        guard.as_mut().unwrap().as_director_ptr() as *mut c_void
    }
}
```

## Lifecycle Hooks

The director provides several lifecycle hooks that are called at different stages:

### Pre-Framework Init
```rust
fn pre_framework_init(&mut self) -> bool
```
Called before the framework initializes. Very early in startup.

### Pre-App Init
```rust
fn pre_app_init(&mut self) -> bool
```
Called before the app initializes.

### Post-App Init ⭐
```rust
fn post_app_init(&mut self) -> bool
```
**This is the main hook for most mods.** Called after the app is initialized.
This is where you typically:
- Get access to the city
- Set up your mod systems
- Register event handlers

Example:
```rust
fn post_app_init(&mut self) -> bool {
    unsafe {
        // Get the framework (you'd need to store this)
        if let Some(framework) = self.get_framework() {
            // Get the app
            if let Some(app) = get_app(framework) {
                // Get the city
                if let Some(city) = app.get_city() {
                    // Wrap in safe API
                    let city = City::from_raw(city).unwrap();

                    // Your mod logic here!
                    self.init_mod(&city);
                }
            }
        }
    }
    true
}
```

### Pre-App Shutdown
```rust
fn pre_app_shutdown(&mut self) -> bool
```
Called before the app shuts down. Clean up your mod here.

### Post-App Shutdown
```rust
fn post_app_shutdown(&mut self) -> bool
```
Called after the app shuts down.

### Post-System Service Shutdown
```rust
fn post_system_service_shutdown(&mut self) -> bool
```
Called after system services shut down. Very late in shutdown.

### Abortive Quit
```rust
fn abortive_quit(&mut self) -> bool
```
Called when the game crashes. Last chance to save data.

### On Install
```rust
fn on_install(&mut self) -> bool
```
Called when the mod is first installed.

## Director Implementation Details

### Multiple Inheritance

In C++, `cRZCOMDllDirector` inherits from both:
- `cIGZCOMDirector`
- `cIGZFrameWorkHooks`

In Rust, we handle this through `QueryInterface`:

```rust
match riid {
    GZIID_IGZCOMDIRECTOR => {
        // Return as director interface
        *ppv_obj = this as *mut c_void;
        true
    }
    GZIID_IGZFRAMEWORKHOOKS => {
        // Return as hooks interface (same pointer, different interface)
        *ppv_obj = this as *mut c_void;
        true
    }
    _ => false
}
```

### Memory Layout

The `RustDirector<T>` struct has this memory layout:

```
┌─────────────────────────────────┐
│  vtable_director: *const _      │  4 bytes (32-bit)
├─────────────────────────────────┤
│  ref_count: u32                 │  4 bytes
├─────────────────────────────────┤
│  director_id: u32               │  4 bytes
├─────────────────────────────────┤
│  com: *mut IGZCOM               │  4 bytes
├─────────────────────────────────┤
│  framework: *mut IGZFrameWork   │  4 bytes
├─────────────────────────────────┤
│  class_factories: HashMap       │  24 bytes
├─────────────────────────────────┤
│  hooks: T                       │  varies
└─────────────────────────────────┘
```

### Vtable Functions

The vtable contains function pointers that the game calls:

```rust
static DIRECTOR_VTABLE: IGZCOMDirectorVTable = IGZCOMDirectorVTable {
    base: IGZUnknownVTable {
        query_interface: director_query_interface,
        add_ref: director_add_ref,
        release: director_release,
    },
    initialize_com: director_initialize_com,
    on_start: director_on_start,
    // ... etc
};
```

Each function follows the `thiscall` calling convention (32-bit Windows).

## Example: Complete Mod

See `examples/complete_mod.rs` for a full working example.

The example demonstrates:
- ✅ Creating a custom director
- ✅ Implementing lifecycle hooks
- ✅ Safe Rust API usage
- ✅ Accessing city information
- ✅ Zero unsafe blocks in user code

## Building Your Mod

```bash
# Install the 32-bit Windows target
rustup target add i686-pc-windows-msvc

# Build your mod
cargo build --release --target i686-pc-windows-msvc

# The DLL will be at:
# target/i686-pc-windows-msvc/release/your_mod_name.dll

# Copy to SimCity 4 plugins folder:
cp target/i686-pc-windows-msvc/release/your_mod_name.dll \
   "C:/Program Files (x86)/Maxis/SimCity 4/Plugins/"
```

## Debugging

### Logging

Use `println!()` for logging. Output will go to stdout, which you can capture with a tool like DebugView.

```rust
fn post_app_init(&mut self) -> bool {
    println!("[MyMod] Initializing...");
    // ... your code
    println!("[MyMod] Initialized!");
    true
}
```

### Panics

If your Rust code panics, the game will likely crash. Always use proper error handling:

```rust
fn post_app_init(&mut self) -> bool {
    // DON'T: panic!("Something went wrong");

    // DO: Return false on error
    if !self.initialize() {
        println!("[MyMod] ERROR: Failed to initialize");
        return false;
    }

    true
}
```

### Unwinding

Make sure panic unwinding doesn't cross the FFI boundary:

```rust
fn post_app_init(&mut self) -> bool {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        // Your code here
    })).unwrap_or_else(|_| {
        println!("[MyMod] PANIC: Caught panic in post_app_init");
        false
    })
}
```

## Advanced: Registering Class Factories

If you want to provide custom COM classes that the game can instantiate:

```rust
impl MyMod {
    fn register_classes(&mut self, director: &mut RustDirector<Self>) {
        // Register a class factory
        director.add_class(MY_CLASS_ID, my_class_factory);
    }
}

unsafe extern "C" fn my_class_factory(
    iid: u32,
    ppv_obj: *mut *mut c_void
) -> bool {
    // Create your class instance
    // Cast to the requested interface
    // Return pointer
    todo!()
}
```

## Safety Considerations

### What's Safe

✅ Implementing `DirectorHooks` - all safe Rust
✅ Using the `City` API - safe wrappers
✅ Your mod logic - safe Rust
✅ Error handling with `Result` - safe

### What's Unsafe

⚠️ Creating the director - uses `unsafe`
⚠️ Interfacing with game objects - FFI boundary
⚠️ Storing pointers from the game - lifetime issues

### Best Practices

1. **Minimize unsafe**: Keep unsafe code in the FFI layer
2. **Validate pointers**: Always check for null before dereferencing
3. **Handle errors**: Don't panic, return false instead
4. **Document assumptions**: Use safety comments on unsafe blocks
5. **Test thoroughly**: Create test harnesses where possible

## Troubleshooting

### Mod doesn't load

- Check that DLL exports `GZDllGetGZCOMDirector`
- Verify 32-bit target (i686-pc-windows-msvc)
- Check for missing dependencies

### Game crashes on load

- Ensure vtable layout matches C++ exactly
- Check calling convention (`thiscall`)
- Verify no panics in hook implementations

### Hooks not called

- Check director ID is unique
- Verify hook returns true
- Make sure director is properly initialized

## Next Steps

- Read `VTABLE_INSIGHTS.md` for deep dive on vtable layout
- See `examples/complete_mod.rs` for working example
- Check `city_safe.rs` for City API reference
- Read `README.md` for overall architecture

## Resources

- [gzcom-dll repository](https://github.com/nsgomez/gzcom-dll)
- [Rust FFI documentation](https://doc.rust-lang.org/nomicon/ffi.html)
- [SimCity 4 modding community](https://community.simtropolis.com/)
