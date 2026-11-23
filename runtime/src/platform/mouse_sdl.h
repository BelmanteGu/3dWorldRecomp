#pragma once
#include <SDL3/SDL.h>
namespace mods {
bool handle_mouse_event(const SDL_Event& event);
void update_mouse();
bool mouse_captured();
void mouse_init(void* tv_window);
}
