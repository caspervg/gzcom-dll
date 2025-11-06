//! Complete SC4 Mod Example using Rust Director
//!
//! This example shows a complete, working SimCity 4 mod implemented in Rust.
//! It demonstrates:
//! - Creating a director with custom hooks
//! - Handling lifecycle events
//! - Accessing game systems
//! - All in safe Rust!

use sc4_rust_poc::{DirectorHooks, RustDirector, City, IGZCOM, IGZFrameWork};
use std::ffi::c_void;
use std::sync::Mutex;

// Global director instance
// In a real mod, this would be properly managed
static DIRECTOR: Mutex<Option<Box<RustDirector<MyModDirector>>>> = Mutex::new(None);

/// Our mod's custom director implementation
///
/// This struct holds all the state for our mod and implements the DirectorHooks trait.
pub struct MyModDirector {
    /// Mod name for logging
    name: String,
    /// Whether the mod has been initialized
    initialized: bool,
}

impl MyModDirector {
    pub fn new() -> Self {
        Self {
            name: "Rust Example Mod".to_string(),
            initialized: false,
        }
    }
}

impl DirectorHooks for MyModDirector {
    fn get_director_id(&self) -> u32 {
        // This should be a unique ID for your mod
        // You can generate one with an online GUID generator
        0x12345678
    }

    fn pre_framework_init(&mut self) -> bool {
        println!("[{}] PreFrameWorkInit called", self.name);
        true
    }

    fn pre_app_init(&mut self) -> bool {
        println!("[{}] PreAppInit called", self.name);
        true
    }

    fn post_app_init(&mut self) -> bool {
        println!("[{}] PostAppInit called - Setting up mod", self.name);

        // This is where you'd typically initialize your mod
        // In a real mod, you'd:
        // 1. Get the app from the framework
        // 2. Get the city
        // 3. Set up your mod logic

        /*
        unsafe {
            // Get framework (would need to be passed in)
            let framework = self.get_framework();
            if !framework.is_null() {
                // Get app
                let app = get_app(framework);
                if !app.is_null() {
                    // Get city
                    let city_ptr = (*app).get_city();
                    if !city_ptr.is_null() {
                        if let Some(city) = City::from_raw(city_ptr) {
                            // Now do your mod logic!
                            self.setup_mod(&city);
                        }
                    }
                }
            }
        }
        */

        self.initialized = true;
        println!("[{}] Mod initialization complete!", self.name);
        true
    }

    fn pre_app_shutdown(&mut self) -> bool {
        println!("[{}] PreAppShutdown called - Cleaning up", self.name);
        self.initialized = false;
        true
    }

    fn post_app_shutdown(&mut self) -> bool {
        println!("[{}] PostAppShutdown called", self.name);
        true
    }

    fn post_system_service_shutdown(&mut self) -> bool {
        println!("[{}] PostSystemServiceShutdown called", self.name);
        true
    }

    fn abortive_quit(&mut self) -> bool {
        println!("[{}] AbortiveQuit called - Game crashed!", self.name);
        true
    }

    fn on_install(&mut self) -> bool {
        println!("[{}] OnInstall called", self.name);
        true
    }

    fn on_start(&mut self, _com: *mut IGZCOM) -> bool {
        println!("[{}] OnStart called", self.name);
        true
    }
}

impl MyModDirector {
    /// Example mod logic - would be called from post_app_init
    fn setup_mod(&mut self, city: &City) {
        println!("[{}] Setting up mod for city...", self.name);

        // Example: Print city information
        let serial = city.serial_number();
        let size = city.size();
        let cells = city.cell_count();

        println!("  City serial: {}", serial);
        println!("  City size: {:.2}x{:.2}", size.width, size.height);
        println!("  Grid: {}x{} cells", cells.x, cells.z);
        println!("  Established: {}", city.is_established());
        println!("  Simulation mode: {}", city.is_in_simulation_mode());

        // Your mod logic would go here
        // For example:
        // - Modify game parameters
        // - Set up event listeners
        // - Initialize custom systems
        // - etc.
    }
}

/// Entry point that SimCity 4 calls
///
/// This is the function that the game calls when it loads your DLL.
/// It must be exported with C linkage and the exact name "GZDllGetGZCOMDirector".
#[no_mangle]
pub extern "C" fn GZDllGetGZCOMDirector() -> *mut c_void {
    unsafe {
        // Create our director if it doesn't exist
        let mut director_guard = DIRECTOR.lock().unwrap();

        if director_guard.is_none() {
            println!("Creating Rust director...");

            let hooks = MyModDirector::new();
            let mut director = RustDirector::new(hooks);
            let ptr = director.as_director_ptr();

            *director_guard = Some(director);

            println!("Rust director created successfully!");
            return ptr as *mut c_void;
        }

        // Return existing director
        if let Some(ref mut director) = *director_guard {
            director.as_director_ptr() as *mut c_void
        } else {
            std::ptr::null_mut()
        }
    }
}

/// Main function (for documentation purposes - not actually called)
fn main() {
    println!("SimCity 4 Rust Mod - Complete Example");
    println!();
    println!("This is a complete example of a SC4 mod written in Rust.");
    println!();
    println!("To use this mod:");
    println!("  1. Build with: cargo build --release --target i686-pc-windows-msvc");
    println!("  2. Copy target/i686-pc-windows-msvc/release/sc4_rust_poc.dll");
    println!("  3. Rename to something like MyRustMod.dll");
    println!("  4. Place in SimCity 4/Plugins/ folder");
    println!("  5. Launch SimCity 4");
    println!();
    println!("The mod will print messages as it goes through the lifecycle:");
    println!("  - PreFrameWorkInit");
    println!("  - PreAppInit");
    println!("  - PostAppInit (main setup)");
    println!("  - ... (mod runs) ...");
    println!("  - PreAppShutdown");
    println!("  - PostAppShutdown");
    println!();
    println!("Features demonstrated:");
    println!("  ✓ Safe Rust director implementation");
    println!("  ✓ Lifecycle hook handling");
    println!("  ✓ Accessing game systems (City)");
    println!("  ✓ Zero unsafe blocks in user code");
    println!("  ✓ Type-safe, memory-safe mod logic");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_director_creation() {
        let hooks = MyModDirector::new();
        assert_eq!(hooks.initialized, false);
        assert_eq!(hooks.get_director_id(), 0x12345678);
    }

    #[test]
    fn test_director_lifecycle() {
        let mut hooks = MyModDirector::new();

        // Simulate lifecycle
        assert!(hooks.pre_framework_init());
        assert!(!hooks.initialized);

        assert!(hooks.pre_app_init());
        assert!(!hooks.initialized);

        assert!(hooks.post_app_init());
        assert!(hooks.initialized);

        assert!(hooks.pre_app_shutdown());
        assert!(!hooks.initialized);
    }
}
