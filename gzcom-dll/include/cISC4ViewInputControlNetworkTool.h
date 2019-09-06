#pragma once
#include "cIGZUnknown.h"
#include "cISC4ViewInputControl.h"
#include <list>

//class cISC4DisasterInstance;
class cIGZCursor;

class cISC4ViewInputControlNetworkTool : public cISC4ViewInputControl
{
	public:
		virtual bool Init(void) = 0;
		virtual bool Shutdown(void) = 0;
		

};