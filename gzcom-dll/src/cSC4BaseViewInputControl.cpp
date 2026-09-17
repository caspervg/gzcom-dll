/*
 * gzcom-dll - an open-source DLL Plugin SDK for SimCity 4
 *
 * cSC4BaseViewInputControl.cpp
 *
 * Copyright (C) 2025 Nicholas Hayes
 * Copyright (C) 2026 Casper Van Gheluwe
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

#include "cSC4BaseViewInputControl.h"
#include "cIGZCanvas.h"
#include "cIGZCursorManager.h"
#include "cIGZKeyboard.h"
#include "GZServPtrs.h"
#include "SC4UI.h"
#include <windowsx.h>

cSC4BaseViewInputControl::cSC4BaseViewInputControl(uint32_t controlID)
	: initialized(false),
	  middleMouseDown(false),
	  winProcFilterRegistered(false),
	  id(controlID),
	  cursorID(0)
{
}

bool cSC4BaseViewInputControl::QueryInterface(uint32_t riid, void** ppvObj)
{
	if (riid == GZIID_cISC4ViewInputControl)
	{
		*ppvObj = static_cast<cISC4ViewInputControl*>(this);
		AddRef();

		return true;
	}

	return cRZBaseUnknown::QueryInterface(riid, ppvObj);
}

uint32_t cSC4BaseViewInputControl::AddRef()
{
	return cRZBaseUnknown::AddRef();
}

uint32_t cSC4BaseViewInputControl::Release()
{
	return cRZBaseUnknown::Release();
}

bool cSC4BaseViewInputControl::Init()
{
	if (!initialized)
	{
		cIGZWinMgrPtr localWindowManager;

		if (localWindowManager)
		{
			auto localView3D = SC4UI::GetView3DWin();

			if (localView3D)
			{
				cIGZCanvas* canvas = localWindowManager->GetCanvas();

				if (canvas && canvas->QueryInterface(GZIID_cIGZCanvasW32, canvasW32.AsPPVoid()))
				{
					winProcFilterRegistered = canvasW32->AddWinProcFilter(
						static_cast<cIGZWinProcFilterW32*>(this),
						true);
				}

				if (!cursor && cursorID != 0)
				{
					cIGZCursorManager* pCursorManager = localWindowManager->GetCursorManager();

					if (pCursorManager)
					{
						cIGZCursor* pCursor = pCursorManager->GetCursor(cursorID);

						if (pCursor)
						{
							cursor = pCursor;

							if (window)
							{
								window->SetCursor(cursor, true);
							}
						}
					}
				}

				view3D = localView3D;
				windowManager = localWindowManager;
				initialized = true;
			}
		}
	}

	return initialized;
}

bool cSC4BaseViewInputControl::Shutdown()
{
	if (initialized)
	{
		if (winProcFilterRegistered && canvasW32)
		{
			canvasW32->AddWinProcFilter(static_cast<cIGZWinProcFilterW32*>(this), false);
		}

		winProcFilterRegistered = false;
		middleMouseDown = false;
		canvasW32.Reset();
		cursor.Reset();
		window.Reset();
		windowManager.Reset();
		view3D.Reset();
		initialized = false;
	}

	return true;
}

uint32_t cSC4BaseViewInputControl::GetID()
{
	return id;
}

void cSC4BaseViewInputControl::SetID(uint32_t id)
{
	this->id = id;
}

cIGZCursor* cSC4BaseViewInputControl::GetCursor()
{
	return cursor;
}

void cSC4BaseViewInputControl::SetCursor(cIGZCursor* pIGZursor)
{
	if (this->cursor != cursor)
	{
		this->cursor = cursor;

		if (cursor && initialized && window)
		{
			window->SetCursor(cursor, true);
		}
	}
}

void cSC4BaseViewInputControl::SetCursor(uint32_t cursorID)
{
	if (cursor != 0)
	{
		cIGZWinMgrPtr localWindowManager;

		if (localWindowManager)
		{
			cIGZCursorManager* pCursorManager = localWindowManager->GetCursorManager();

			if (pCursorManager)
			{
				cIGZCursor* pCursor = pCursorManager->GetCursor(cursorID);

				if (pCursor)
				{
					this->cursorID = cursorID;
					SetCursor(pCursor);
				}
			}
		}
	}
}

void cSC4BaseViewInputControl::SetWindow(cIGZWin* pWin)
{
	window = pWin;
}

bool cSC4BaseViewInputControl::IsSelfScrollingView()
{
	return false;
}

bool cSC4BaseViewInputControl::ShouldStack()
{
	return true;
}

bool cSC4BaseViewInputControl::OnCharacter(char value)
{
	return false;
}

bool cSC4BaseViewInputControl::OnKeyDown(int32_t vkCode, uint32_t modifiers)
{
	return false;
}

bool cSC4BaseViewInputControl::OnKeyUp(int32_t vkCode, uint32_t modifiers)
{
	return false;
}

bool cSC4BaseViewInputControl::OnMouseDownL(int32_t x, int32_t z, uint32_t modifiers)
{
	return false;
}

bool cSC4BaseViewInputControl::OnMouseDownR(int32_t x, int32_t z, uint32_t modifiers)
{
	return false;
}

bool cSC4BaseViewInputControl::OnMouseUpL(int32_t x, int32_t z, uint32_t modifiers)
{
	return false;
}

bool cSC4BaseViewInputControl::OnMouseUpR(int32_t x, int32_t z, uint32_t modifiers)
{
	return false;
}

bool cSC4BaseViewInputControl::OnMouseDownM(int32_t x, int32_t z, uint32_t modifiers)
{
	return false;
}

bool cSC4BaseViewInputControl::OnMouseUpM(int32_t x, int32_t z, uint32_t modifiers)
{
	return false;
}

bool cSC4BaseViewInputControl::OnMouseMove(int32_t x, int32_t z, uint32_t modifiers)
{
	return false;
}

bool cSC4BaseViewInputControl::OnMouseWheel(int32_t x, int32_t z, uint32_t modifiers, int32_t wheelDelta)
{
	return false;
}

bool cSC4BaseViewInputControl::OnMouseExit()
{
	return false;
}

void cSC4BaseViewInputControl::Activate()
{
}

void cSC4BaseViewInputControl::Deactivate()
{
	middleMouseDown = false;
}

bool cSC4BaseViewInputControl::AmCapturing()
{
	bool capturing = false;

	if (window && windowManager)
	{
		cIGZWin* capturingWindow = windowManager->GZGetCapture();
		capturing = window == capturingWindow;
	}

	return capturing;
}

void cSC4BaseViewInputControl::EndInput()
{
	if (view3D)
	{
		view3D->RemoveCurrentViewInputControl(false);
	}
}

bool cSC4BaseViewInputControl::IsOnTop()
{
	bool result = false;

	if (view3D)
	{
		cISC4ViewInputControl* topControl = view3D->GetCurrentViewInputControl();
		result = topControl == this;
	}

	return result;
}

bool cSC4BaseViewInputControl::ReleaseCapture()
{
	bool result = false;

	if (window && windowManager)
	{
		result = windowManager->GZReleaseCapture(window);
	}

	return result;
}

bool cSC4BaseViewInputControl::SetCapture()
{
	bool result = false;

	if (window && windowManager)
	{
		result = windowManager->GZSetCapture(window);
	}

	return result;
}

bool cSC4BaseViewInputControl::IsMiddleMouseDown() const
{
	return middleMouseDown;
}

LRESULT cSC4BaseViewInputControl::FilterMessage(
	HWND hWnd,
	UINT uMsg,
	WPARAM wParam,
	LPARAM lParam,
	bool& handled)
{
	(void)wParam;

	if (uMsg == WM_CANCELMODE || uMsg == WM_CAPTURECHANGED || uMsg == WM_KILLFOCUS)
	{
		middleMouseDown = false;
		return 0;
	}

	const bool isMiddleButtonDownMessage =
		uMsg == WM_MBUTTONDOWN || uMsg == WM_MBUTTONDBLCLK;
	const bool isMiddleButtonUpMessage = uMsg == WM_MBUTTONUP;

	if (!isMiddleButtonDownMessage && !isMiddleButtonUpMessage)
	{
		return 0;
	}

	// Always clear the state on button-up, even if this control stopped being
	// the top control while the button was held.
	if (isMiddleButtonUpMessage)
	{
		middleMouseDown = false;
	}

	if (!initialized || !window || !IsOnTop())
	{
		return 0;
	}

	int32_t x = GET_X_LPARAM(lParam);
	int32_t z = GET_Y_LPARAM(lParam);
	POINT point = { x, z };

	if (!ClientToScreen(hWnd, &point))
	{
		return 0;
	}

	x = point.x;
	z = point.y;

	if (!window->ScreenToWindowCoordinates(x, z))
	{
		return 0;
	}

	uint32_t modifiers = 0;
	cIGZKeyboard* keyboard = windowManager ? windowManager->GetKeyboard() : nullptr;

	if (keyboard)
	{
		modifiers = keyboard->GetCurrentModifierState();
	}

	bool eventHandled = false;

	if (isMiddleButtonDownMessage)
	{
		middleMouseDown = true;
		eventHandled = OnMouseDownM(x, z, modifiers);
	}
	else
	{
		eventHandled = OnMouseUpM(x, z, modifiers);
	}

	if (eventHandled)
	{
		handled = true;
	}

	return 0;
}
