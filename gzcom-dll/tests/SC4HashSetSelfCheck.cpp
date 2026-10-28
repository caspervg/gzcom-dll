/*
 * gzcom-dll - an open-source DLL Plugin SDK for SimCity 4
 *
 * SC4HashSetSelfCheck.cpp
 *
 * Behaviour checks for SC4HashSet. The buckets here are built by hand with
 * the layout and hashes of SimCity 4.exe 1.1.641, the way a game function
 * would leave them. Build and run with
 *   cl /nologo /std:c++20 /W4 /EHsc SC4HashSetSelfCheck.cpp
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

#include "../include/SC4HashSet.h"

#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <set>

// The game allocator is not available outside the game, so the checks use
// malloc and count live blocks to catch leaks and double frees.
static int gLiveBlocks = 0;

struct TestAllocator
{
	static void* Allocate(size_t size)
	{
		++gLiveBlocks;
		return std::malloc(size);
	}

	static void Deallocate(void* ptr)
	{
		--gLiveBlocks;
		std::free(ptr);
	}
};

template<typename Key>
using TestSet = SC4HashSetImpl<Key, TestAllocator>;

// What the game sees when it is handed one of these containers.
template<typename Key>
struct RawHashSet
{
	void* unused;
	SC4HashNode<Key>** start;
	SC4HashNode<Key>** end;
	SC4HashNode<Key>** capacity;
	uint32_t count;
};

template<typename Key>
static RawHashSet<Key>& Raw(TestSet<Key>& set)
{
	static_assert(sizeof(TestSet<Key>) == sizeof(RawHashSet<Key>));
	return reinterpret_cast<RawHashSet<Key>&>(set);
}

// The bucket vector the game allocates before its first insert.
template<typename Key>
static void MakeBuckets(TestSet<Key>& set, size_t count)
{
	RawHashSet<Key>& raw = Raw(set);
	raw.start = static_cast<SC4HashNode<Key>**>(TestAllocator::Allocate(count * sizeof(SC4HashNode<Key>*)));
	raw.end = raw.start + count;
	raw.capacity = raw.end;
	raw.count = 0;

	for (size_t i = 0; i < count; i++)
	{
		raw.start[i] = nullptr;
	}
}

// The game's insert hashes the key and pushes the node on the front of its bucket.
static size_t GameHash(uint32_t key, size_t buckets) { return key % buckets; }
static size_t GameHash(const void* key, size_t buckets) { return (reinterpret_cast<uintptr_t>(key) >> 2) % buckets; }

template<typename Key>
static void GameInsert(TestSet<Key>& set, Key key)
{
	RawHashSet<Key>& raw = Raw(set);
	size_t index = GameHash(key, static_cast<size_t>(raw.end - raw.start));

	auto node = static_cast<SC4HashNode<Key>*>(TestAllocator::Allocate(sizeof(SC4HashNode<Key>)));
	node->key = key;
	node->next = raw.start[index];
	raw.start[index] = node;
	++raw.count;
}

template<typename Key>
static std::multiset<Key> Collect(const TestSet<Key>& set)
{
	std::multiset<Key> keys;

	for (auto it = set.begin(); it != set.end(); ++it)
	{
		keys.insert(*it);
	}

	return keys;
}

struct Unknown : public cIGZUnknown
{
	int refs = 1;

	bool QueryInterface(uint32_t, void**) override { return false; }
	uint32_t AddRef() override { return ++refs; }
	uint32_t Release() override { return --refs; }
};

int main()
{
	// A set the game never filled has no bucket array at all.
	{
		TestSet<uint32_t> set;
		assert(set.bucketCount() == 0);
		assert(set.size() == 0);
		assert(set.begin() == set.end());
		assert(set.find(1) == set.end());
	}
	assert(gLiveBlocks == 0);

	// Buckets without nodes still get freed.
	{
		TestSet<uint32_t> set;
		MakeBuckets(set, 53);
		assert(set.bucketCount() == 53);
		assert(set.begin() == set.end());
		assert(set.find(7) == set.end());
	}
	assert(gLiveBlocks == 0);

	// Integral keys go in bucket key % n. Keys 5, 12 and 19 share bucket 5
	// out of 7, and 12 >> 2 would have put 12 in bucket 3.
	{
		TestSet<uint32_t> set;
		MakeBuckets(set, 7);
		const uint32_t keys[] = { 5, 12, 19, 0, 6, 0x6534284a, 0xffffffff };

		for (uint32_t key : keys)
		{
			GameInsert(set, key);
		}

		assert(set.size() == 7);
		assert(set.bucketIndex(12) == 5);

		for (uint32_t key : keys)
		{
			auto it = set.find(key);
			assert(it != set.end());
			assert(*it == key);
		}

		assert(set.find(26) == set.end());
		assert(set.find(1) == set.end());
		assert(Collect(set) == std::multiset<uint32_t>(std::begin(keys), std::end(keys)));

		// Stepping on from a found node walks the rest of the table.
		auto it = set.find(5);
		size_t remaining = 0;
		for (; it != set.end(); ++it) ++remaining;
		assert(remaining >= 1 && remaining <= 7);
	}
	assert(gLiveBlocks == 0);

	// Pointer keys go in bucket (key >> 2) % n.
	{
		static uint32_t storage[32];
		TestSet<uint32_t*> set;
		MakeBuckets(set, 7);

		bool differsFromPlainModulo = false;

		for (int i = 0; i < 32; i += 3)
		{
			uint32_t* key = &storage[i];
			GameInsert(set, key);
			differsFromPlainModulo |= set.bucketIndex(key) != reinterpret_cast<uintptr_t>(key) % 7;
		}

		assert(differsFromPlainModulo);
		assert(set.size() == 11);

		for (int i = 0; i < 32; i++)
		{
			auto it = set.find(&storage[i]);
			assert((it != set.end()) == (i % 3 == 0));
			if (it != set.end()) assert(*it == &storage[i]);
		}

		assert(Collect(set).size() == 11);
	}
	assert(gLiveBlocks == 0);

	// Interface keys are released once each when the set goes away.
	{
		Unknown objects[5];
		{
			TestSet<Unknown*> set;
			MakeBuckets(set, 3);

			for (Unknown& object : objects)
			{
				GameInsert(set, &object);
			}

			assert(set.find(&objects[2]) != set.end());
		}

		for (const Unknown& object : objects)
		{
			assert(object.refs == 0);
		}
	}
	assert(gLiveBlocks == 0);

	std::puts("SC4HashSet self-check passed");
	return 0;
}
