#pragma once
#include "cIGZUnknown.h"
#include "cIGZWin.h"
#include <list>

//class cISC4DisasterInstance;
class cIGZCursor;

class cISC4ViewInputControl : public cIGZUnknown
{
	public:
		virtual bool Init(void) = 0;
		virtual bool Shutdown(void) = 0;

		virtual uint32_t GetID(void) = 0;
		virtual bool SetID(uint32_t dwId) = 0;
		
		virtual bool IsKeyToBePassed(uint32_t unknown1, uint32_t unknown2) = 0;
		virtual bool IsOnTop(void) = 0;
		virtual bool IsSelfScrollingView(void) = 0;

		virtual bool OnCharacter(char) = 0;
		virtual bool OnKeyDown(uint32_t unknown1, uint32_t unknown2) = 0;
		virtual bool OnKeyUp(uint32_t unknown1, uint32_t unknown2) = 0;
		virtual bool OnMouseDownL(int32_t unknown1, int32_t unknown2, uint32_t unknown3) = 0;
		virtual bool OnMouseDownR(int32_t unknown1, int32_t unknown2, uint32_t unknown3) = 0;
		virtual bool OnMouseExit() = 0;
		virtual bool OnMouseMove(int32_t unknown1, int32_t unknown2, uint32_t unknown3) = 0;
		virtual bool OnMouseUpL(int32_t unknown1, int32_t unknown2, uint32_t unknown3) = 0;
		virtual bool OnMouseUpR(int32_t unknown1, int32_t unknown2, uint32_t unknown3) = 0;
		virtual bool OnMouseWheel(int32_t unknown1, int32_t unknown2, uint32_t unknown3, int32_t unknown4) = 0;

		virtual bool PlayResultSound(int unknown) = 0;

		virtual bool ReleaseCapture(void) = 0;
		virtual bool SetCapture(void) = 0;
		virtual bool SetCursor(cIGZCursor* cursor) = 0;
		virtual bool SetCursor(uint32_t dwCursor) = 0;

		virtual bool SetWindow(cIGZWin* window) = 0;
		virtual bool ShouldStack(void) = 0;

};