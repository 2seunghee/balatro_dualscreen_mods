-- balatro-dualscreen-thor -- swapped screens: the hand on THIS screen, the
-- table on the other one.
--
-- WHY. The companion window is whatever display Android reports as the
-- presentation display, and on some devices that is the screen WITHOUT
-- touch (issue #2), or the top one (the RG DS, issue #3). The hand is
-- useless where it cannot be touched. Moving the window is not an option --
-- a Presentation can only live on a presentation display -- so the CONTENT
-- moves instead: the panel render goes onto the game's own window, and the
-- game's frame is mirrored to the companion.
--
-- The split that already exists makes this cheap. Screen 1 draws G.CANVAS
-- minus whatever the sections own; the sections draw into the panel canvas.
-- Swapping is deciding which canvas goes to which surface, plus routing the
-- input the other way round:
--
--   this window's touches   -> the panel's hash (DS.HASH2), through the same
--                              CLICK / HOVER / DRAG events the companion
--                              sends, so events.lua does not know or care.
--   companion taps          -> the game's own G.DRAW_HASH, resolved the same
--                              way Controller:get_collision_list does, then
--                              clicked through the node's own click().
--
-- Vanilla's own touch handling on this window is suppressed while swapped:
-- what is under the finger is the panel, not the room, and letting both
-- resolve a tap would act on two things at once.
--
-- NULL PATH: swap.active() is false unless the setting is on AND the
-- companion is up AND dual screen is enabled, and every hook below checks it
-- on each call. With it off, every function here is a one-line return.
--
-- UNTESTED ON HARDWARE at the time of writing: the author's device presents
-- the right screen, so this was written against the code and the engine's
-- documented behaviour, not against a swapped device. It ships behind an
-- off-by-default toggle labelled as such.

local swap = {}

local pending = {}        -- events synthesised from this window's touches
local down_x, down_y = 0, 0
local drag_card = -1
local dragging = false
local touching = false
local DRAG_SLOP_PX = 24   -- same as CompanionProbeView.DRAG_SLOP

-- Card rects on the panel, in panel pixels, kept for the drag seed. Java's
-- copy is withheld while swapped (see init.lua) because its taps are in the
-- companion's pixel space, which these are not.
swap.rects = nil

function swap.active()
    if not DS.active then return false end
    local settings = require("dualscreen.settings")
    return settings.swap()
end

--- Panel pixel size while swapped: this window's pixel dimensions. The panel
--- canvas is drawn back onto the window scaled to its unit size, so touches
--- (which arrive in units) are scaled by the same ratio.
function swap.window_pixels()
    if love.graphics.getPixelDimensions then
        return love.graphics.getPixelDimensions()
    end
    return love.graphics.getDimensions()
end

local function to_panel(x, y)
    local uw, uh = love.graphics.getDimensions()
    local pw, ph = DS.panel_w or 0, DS.panel_h or 0
    if uw <= 0 or uh <= 0 or pw <= 0 or ph <= 0 then return x, y end
    return math.floor(x * pw / uw), math.floor(y * ph / uh)
end

local function queue(name, arg)
    pending[#pending + 1] = { name = name, arg = arg, generation = nil }
end

--- Drain the synthesised events. Called from love.update next to the
--- companion's own queue, and applied through the same dispatcher.
function swap.poll_events()
    if #pending == 0 then return pending end
    local out = pending
    pending = {}
    return out
end

local function card_at(px, py)
    local rects = swap.rects
    if not rects then return -1 end
    for i = #rects, 1, -1 do
        local r = rects[i]
        if r.w > 0 and r.h > 0 and px >= r.x and px < r.x + r.w
           and py >= r.y and py < r.y + r.h then
            return i - 1   -- Java's 0-based index, which DRAG_CARD expects
        end
    end
    return -1
end

--------------------------------------------------------------------------
-- This window's touches -> panel events.
--------------------------------------------------------------------------

function swap.pressed(x, y, button)
    if not swap.active() then return false end
    if button ~= 1 then return true end   -- swallow, nothing to do
    local px, py = to_panel(x, y)
    down_x, down_y = x, y
    touching, dragging = true, false
    drag_card = card_at(px, py)
    queue("HOVER_AT", px .. "|" .. py)
    return true
end

function swap.moved(x, y)
    if not swap.active() then return false end
    if not touching or drag_card < 0 then return true end
    if not dragging then
        local dx, dy = x - down_x, y - down_y
        -- Slop is specified in panel pixels, like the Java side's.
        local sx = (DS.panel_w or 1) / math.max(1, (love.graphics.getWidth()))
        if (dx * dx + dy * dy) * sx * sx < DRAG_SLOP_PX * DRAG_SLOP_PX then
            return true
        end
        dragging = true
    end
    local px, py = to_panel(x, y)
    queue("DRAG_CARD", drag_card .. "|" .. px .. "|" .. py)
    return true
end

function swap.released(x, y, button)
    if not swap.active() then return false end
    if button ~= 1 or not touching then return true end
    touching = false
    if dragging then
        dragging = false
        drag_card = -1
        queue("DRAG_END", "")
        queue("HOVER_END", "")
        return true
    end
    drag_card = -1
    local px, py = to_panel(x, y)
    queue("HOVER_END", "")
    queue("CLICK", px .. "|" .. py)
    return true
end

--- A finger lifted by the system (focus loss) must revert, never commit.
function swap.cancel()
    if not touching then return end
    touching = false
    if dragging then queue("DRAG_END", "") end
    dragging, drag_card = false, -1
    queue("HOVER_END", "")
end

--------------------------------------------------------------------------
-- Companion taps -> the game's own hash.
--------------------------------------------------------------------------

--- Mirror geometry: how G.CANVAS was fitted into the companion. Must agree
--- with render.draw_mirror, which is why both read it from here.
function swap.mirror_fit(pw, ph)
    local src = G and G.CANVAS
    if not src or pw <= 0 or ph <= 0 then return nil end
    local cw, ch = src:getDimensions()
    if cw <= 0 or ch <= 0 then return nil end
    local k = math.min(pw / cw, ph / ch)
    return k, (pw - cw * k) * 0.5, (ph - ch * k) * 0.5, cw, ch
end

--- Companion pixel -> absolute tile coordinates in the room, the space
--- Controller tests collisions in (G.CURSOR.T, controller.lua:178: window
--- units over TILESCALE*TILESIZE). G.CANVAS fills the window, so canvas
--- units scale to window units by the ratio of their sizes.
function swap.companion_to_tile(px, py)
    local pw, ph = DS.pres_w or 0, DS.pres_h or 0
    local k, sx, sy, cw, ch = swap.mirror_fit(pw, ph)
    if not k or k <= 0 then return nil end
    local ux, uy = (px - sx) / k, (py - sy) / k
    local ww, wh = love.graphics.getDimensions()
    local wx, wy = ux * ww / cw, uy * wh / ch
    local S = (G.TILESCALE or 1) * (G.TILESIZE or 20)
    if S <= 0 then return nil end
    return wx / S, wy / S
end

--- Topmost node under a companion pixel in G.DRAW_HASH, walked backwards as
--- Controller:get_collision_list does (controller.lua:955-983).
function swap.hit_main(px, py, want_hover)
    local hash = G and G.DRAW_HASH
    if not hash or #hash == 0 then return nil end
    local tx, ty = swap.companion_to_tile(px, py)
    if not tx then return nil end
    local point = { x = tx, y = ty }
    for i = #hash, 1, -1 do
        local v = hash[i]
        if v and not v.REMOVED and v.states
           and ((want_hover and v.states.hover and v.states.hover.can)
                or (not want_hover and v.states.collide and v.states.collide.can))
           and v.collides_with_point and v:collides_with_point(point) then
            return v
        end
    end
    return nil
end

function swap.click_main(px, py)
    -- The intro: same path as DS.click_at, a tap anywhere skips it.
    if G and G.STATE and G.STATES and G.STATE == G.STATES.SPLASH and G.CONTROLLER then
        pcall(function() G.CONTROLLER:queue_L_cursor_press(0, 0) end)
        return true
    end
    local node = swap.hit_main(px, py, false)
    return DS.click_node(node)
end

function swap.hover_main(px, py)
    return swap.hit_main(px, py, true)
end

--------------------------------------------------------------------------
-- Install: the input hooks. Idempotent; init.lua calls it once.
--------------------------------------------------------------------------

local installed = false

function swap.install()
    if installed then return end
    installed = true

    local function hook(name, fn)
        local orig = love[name]
        love[name] = function(...)
            local ok, consumed = pcall(fn, ...)
            if ok and consumed then return end
            if orig then return orig(...) end
        end
    end

    hook("mousepressed",  function(x, y, b) return swap.pressed(x, y, b) end)
    hook("mousereleased", function(x, y, b) return swap.released(x, y, b) end)
    hook("mousemoved",    function(x, y)    return swap.moved(x, y) end)
    -- SDL also reports the finger through the touch callbacks; the mouse
    -- emulation above is the one that carries the work, these only keep
    -- vanilla from seeing the same finger twice.
    hook("touchpressed",  function() return swap.active() end)
    hook("touchreleased", function() return swap.active() end)
    hook("touchmoved",    function() return swap.active() end)

    -- Vanilla reads the cursor from love.mouse each frame for hover and for
    -- drawing G.CURSOR. While swapped the finger is over the panel, not the
    -- room, so the room must see nothing under it.
    local orig_pos = love.mouse.getPosition
    local orig_x, orig_y = love.mouse.getX, love.mouse.getY
    love.mouse.getPosition = function(...)
        if swap.active() then return -100000, -100000 end
        return orig_pos(...)
    end
    love.mouse.getX = function(...)
        if swap.active() then return -100000 end
        return orig_x(...)
    end
    love.mouse.getY = function(...)
        if swap.active() then return -100000 end
        return orig_y(...)
    end

    DS.log("swap: input hooks installed (inert until the setting is on)")
end

return swap
