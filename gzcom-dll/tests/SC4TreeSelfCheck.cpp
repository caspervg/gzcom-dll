/*
 * gzcom-dll - an open-source DLL Plugin SDK for SimCity 4
 *
 * SC4TreeSelfCheck.cpp
 *
 * Behaviour checks for SC4Tree, SC4Map and SC4Set. The trees here are built
 * by hand with the node layout of SimCity 4.exe 1.1.641, the way a game
 * function would leave them. Build and run with
 *   cl /nologo /std:c++20 /W4 /EHsc SC4TreeSelfCheck.cpp
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

#include "../include/SC4Tree.h"
#include "../include/SC4Percentage.h"

#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <vector>

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

// What the game sees when it is handed one of these containers.
struct RawTree
{
	SC4TreeNodeBase* header;
	uint32_t count;
	uint32_t compare;
};

template<typename Tree>
static RawTree& Raw(Tree& tree)
{
	static_assert(sizeof(Tree) == sizeof(RawTree));
	return reinterpret_cast<RawTree&>(tree);
}

template<typename Value>
static SC4TreeNodeBase* MakeNode(const Value& value, uint8_t color)
{
	auto node = static_cast<SC4TreeNode<Value>*>(TestAllocator::Allocate(sizeof(SC4TreeNode<Value>)));
	new (const_cast<void*>(static_cast<const void*>(&node->value))) Value(value);
	node->base.color = color;
	node->base.parent = nullptr;
	node->base.left = nullptr;
	node->base.right = nullptr;
	return &node->base;
}

static void Link(SC4TreeNodeBase* parent, SC4TreeNodeBase* left, SC4TreeNodeBase* right)
{
	parent->left = left;
	parent->right = right;
	if (left) left->parent = parent;
	if (right) right->parent = parent;
}

// Hangs a root under the header the way the game does after inserts.
template<typename Tree>
static void Attach(Tree& tree, SC4TreeNodeBase* root, uint32_t count)
{
	RawTree& raw = Raw(tree);
	raw.header->parent = root;
	root->parent = raw.header;
	raw.header->left = SC4TreeNodeBase::Minimum(root);
	raw.header->right = SC4TreeNodeBase::Maximum(root);
	raw.count = count;
}

template<typename Tree>
static void CheckEmpty(Tree& tree)
{
	RawTree& raw = Raw(tree);
	assert(raw.header != nullptr);
	assert(raw.header->color == SC4TreeNodeBase::Red);
	assert(raw.header->parent == nullptr);
	assert(raw.header->left == raw.header);
	assert(raw.header->right == raw.header);
	assert(raw.count == 0);
	assert(tree.size() == 0);
	assert(tree.empty());
	assert(tree.begin() == tree.end());
}

struct Counted
{
	static int sDestroyed;
	int value;
	~Counted() { ++sDestroyed; }
};
int Counted::sDestroyed = 0;

int main()
{
	using Map = SC4Map<uint32_t, int32_t, TestAllocator>;
	using Set = SC4Set<uint32_t, TestAllocator>;

	static_assert(sizeof(SC4TreeNode<std::pair<const uint32_t, SC4Percentage>>) == 0x18);

	// An empty container owns exactly one block, the header.
	{
		Map map;
		assert(gLiveBlocks == 1);
		CheckEmpty(map);
		assert(map.find(5) == map.end());
		assert(!map.contains(5));
	}
	assert(gLiveBlocks == 0);

	// A balanced tree of five keys iterates in order both ways.
	{
		Map map;
		SC4TreeNodeBase* n10 = MakeNode(std::pair<const uint32_t, int32_t>(10, -1), SC4TreeNodeBase::Red);
		SC4TreeNodeBase* n20 = MakeNode(std::pair<const uint32_t, int32_t>(20, -2), SC4TreeNodeBase::Black);
		SC4TreeNodeBase* n30 = MakeNode(std::pair<const uint32_t, int32_t>(30, -3), SC4TreeNodeBase::Black);
		SC4TreeNodeBase* n40 = MakeNode(std::pair<const uint32_t, int32_t>(40, -4), SC4TreeNodeBase::Black);
		SC4TreeNodeBase* n50 = MakeNode(std::pair<const uint32_t, int32_t>(50, -5), SC4TreeNodeBase::Red);
		Link(n30, n20, n40);
		Link(n20, n10, nullptr);
		Link(n40, nullptr, n50);
		Attach(map, n30, 5);
		assert(gLiveBlocks == 6);
		assert(map.size() == 5);

		std::vector<uint32_t> keys;
		for (const auto& kv : map)
		{
			assert(kv.second == -static_cast<int32_t>(kv.first / 10));
			keys.push_back(kv.first);
		}
		assert((keys == std::vector<uint32_t>{ 10, 20, 30, 40, 50 }));

		keys.clear();
		for (auto it = map.end(); it != map.begin();)
		{
			--it;
			keys.push_back(it->first);
		}
		assert((keys == std::vector<uint32_t>{ 50, 40, 30, 20, 10 }));

		assert(map.find(40)->second == -4);
		assert(map.find(10)->second == -1);
		assert(map.find(35) == map.end());
		assert(map.find(5) == map.end());
		assert(map.find(55) == map.end());

		// Values are writable through a map iterator.
		map.find(20)->second = 7;
		assert(map.find(20)->second == 7);

		map.clear();
		assert(gLiveBlocks == 1);
		CheckEmpty(map);
	}
	assert(gLiveBlocks == 0);

	// A single-node tree steps from the root straight to the header.
	{
		Set set;
		SC4TreeNodeBase* root = MakeNode<const uint32_t>(42u, SC4TreeNodeBase::Black);
		Attach(set, root, 1);
		auto it = set.begin();
		assert(*it == 42u);
		assert(++it == set.end());
		assert(*--it == 42u);
		assert(set.contains(42u));
		assert(!set.contains(41u));
	}
	assert(gLiveBlocks == 0);

	// Destruction runs value destructors once per node, like the game.
	{
		SC4Map<int32_t, Counted, TestAllocator> map;
		SC4TreeNodeBase* a = MakeNode(std::pair<const int32_t, Counted>(1, Counted{ 1 }), SC4TreeNodeBase::Red);
		SC4TreeNodeBase* b = MakeNode(std::pair<const int32_t, Counted>(2, Counted{ 2 }), SC4TreeNodeBase::Black);
		SC4TreeNodeBase* c = MakeNode(std::pair<const int32_t, Counted>(3, Counted{ 3 }), SC4TreeNodeBase::Red);
		Link(b, a, c);
		Attach(map, b, 3);
		Counted::sDestroyed = 0;
	}
	assert(Counted::sDestroyed == 3);
	assert(gLiveBlocks == 0);

	std::puts("SC4Tree self-check passed");
	return 0;
}
