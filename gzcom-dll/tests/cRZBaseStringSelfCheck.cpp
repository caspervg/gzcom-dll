/*
 * gzcom-dll - an open-source DLL Plugin SDK for SimCity 4
 *
 * cRZBaseStringSelfCheck.cpp
 *
 * Behaviour checks for cRZBaseString. Build and run with
 * scripts/check-cRZBaseString.ps1, which also verifies the emitted vtable order
 * against the Windows game layout.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation, under
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see <https://www.gnu.org/licenses/>.
 */

#include "../include/cRZBaseString.h"

#include <cassert>
#include <cstring>
#include <cstdio>

int main()
{
	// Implicit conversion from a literal to the owning string must keep working.
	auto acceptsString = [](cRZBaseString value) { return value.Strlen(); };
	assert(acceptsString("abcdef") == 6);

	cRZBaseString value("abcdef");
	assert(std::strcmp(value.ToChar(), "abcdef") == 0);

	// Erase takes a count, like std::basic_string::erase and like the game.
	cRZBaseString erased("abcdef");
	erased.Erase(1, 2);
	assert(std::strcmp(erased.ToChar(), "adef") == 0);

	// A null argument compares equal to an empty string in both overloads.
	cRZBaseString empty;
	assert(empty.IsEqual(static_cast<char const*>(nullptr), 0, true));
	assert(empty.IsEqual(static_cast<cIGZString const*>(nullptr), true));
	assert(!value.IsEqual(static_cast<char const*>(nullptr), 0, true));
	assert(!value.IsEqual(static_cast<cIGZString const*>(nullptr), true));

	// Sprintf keeps the last character and the terminator.
	cRZBaseString formatted;
	formatted.Sprintf("%s-%d", "abc", 42);
	assert(std::strcmp(formatted.ToChar(), "abc-42") == 0);
	assert(formatted.Strlen() == 6);

	// Case-insensitive search and compare.
	cRZBaseString haystack("Hello World");
	assert(haystack.Find("world", 0, false) == 6);
	assert(haystack.Find("world", 0, true) == -1);
	assert(haystack.RFind("o", 10, true) == 7);
	assert(haystack.CompareTo(cRZBaseString("HELLO WORLD"), false) == 0);
	assert(haystack.CompareTo(cRZBaseString("HELLO WORLD"), true) != 0);

	// The reference count belongs to the object, so copies start at zero.
	cRZBaseString counted("abc");
	counted.AddRef();
	counted.AddRef();
	cRZBaseString copy(counted);
	assert(copy.Release() == 0);
	cRZBaseString assigned;
	assigned = counted;
	assert(assigned.Release() == 0);
	assert(counted.Release() == 1);

	// QueryInterface answers for all three ids and rejects anything else.
	void* object = nullptr;
	assert(value.QueryInterface(GZIID_cIGZUnknown, &object) && object != nullptr);
	assert(value.QueryInterface(GZIID_cIGZString, &object) && object != nullptr);
	assert(value.QueryInterface(GZIID_cRZBaseString, &object) && object != nullptr);
	assert(!value.QueryInterface(0xdeadbeef, &object) && object == nullptr);

	std::printf("cRZBaseString self-check passed\n");
	return 0;
}
