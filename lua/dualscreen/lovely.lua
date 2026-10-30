-- balatro-dualscreen-thor -- the runtime half of mod support.
--
-- Mods are patched into the game's sources when it is assembled (see
-- tools/lovely.py and GameAssembler.java), so by the time this runs every
-- pattern, regex and copy patch is already in the files. What Lovely does at
-- RUNTIME, and this file stands in for, is small:
--
--   * `require "lovely"` -- the metadata module (mod_dir, version, repo).
--     Steamodded reads mod_dir from it to find the Mods folder.
--   * [patches.module] registration: each module's source goes into
--     package.preload under its name, exactly as Lovely does, and load_now
--     modules are run just before the file they were declared `before`.
--
-- Loaded by the ONE line the assembler prepends to main.lua when mods are
-- present. With no mods installed, nothing prepends it and this file is
-- never required: the no-mod build is byte-identical to the one before mod
-- support existed.
--
-- Nothing here touches the game. It only fills package.preload.

local manifest = nil
do
    local ok, chunk = pcall(love.filesystem.load, "dualscreen-mods/manifest.lua")
    if ok and chunk then
        local ok2, m = pcall(chunk)
        if ok2 and type(m) == "table" then manifest = m end
    end
end

local function log(msg)
    print("[dualscreen] lovely: " .. tostring(msg))
end

if not manifest then
    log("no manifest; the shim was required without mods being assembled")
    manifest = { mod_dir = "", version = "0.0.0-dualscreen", repo = "", mods = {}, modules = {} }
end

-- Mirrors lovely-core's inject_metadata: the same field names, so a mod that
-- reads them sees what it expects. The two functions that re-patch at runtime
-- cannot exist here -- patching happened at assembly -- and say so.
local vars = {}
local lovely = {
    repo     = manifest.repo,
    version  = manifest.version,
    mod_dir  = manifest.mod_dir,
    log_path = manifest.mod_dir .. "/lovely/log/dualscreen.log",
    is_dualscreen = true,
    apply_patches = function()
        return false, "patches are applied when the game is assembled"
    end,
    reload_patches = function()
        return false, "patches are applied when the game is assembled"
    end,
    set_var = function(k, v) vars[tostring(k)] = tostring(v) end,
    get_var = function(k) return vars[tostring(k)] end,
    remove_var = function(k) vars[tostring(k)] = nil end,
}

package.preload["lovely"] = function() return lovely end

-- One compiled chunk per module, keyed by name; load_now wraps the result.
local chunks = {}

local function compile(m)
    local src = love.filesystem.read(m.file)
    if not src then
        return nil, "module source missing from the game: " .. tostring(m.file)
    end
    return loadstring(src, m.chunk)
end

for _, m in ipairs(manifest.modules or {}) do
    local chunk, err = compile(m)
    if chunk then
        chunks[m.name] = chunk
        if not m.load_now then
            package.preload[m.name] = chunk
        end
    else
        -- Lovely stores an error-raising closure so the failure surfaces at
        -- require time with the real message, not as "module not found".
        local msg = tostring(err)
        package.preload[m.name] = function() error(msg, 0) end
        log("module " .. tostring(m.name) .. " failed to compile: " .. msg)
    end
end

local shim = {}

--- Run a load_now module and preload its return value. Called from the line
--- the assembler prepends to the module's `before` target; each name runs
--- once, as Lovely runs it once.
local ran = {}

function shim.load_now(name)
    if ran[name] then return end
    local chunk = chunks[name]
    if not chunk then
        log("load_now for unknown module " .. tostring(name))
        return
    end
    ran[name] = true
    chunks[name] = nil
    local ok, value = pcall(chunk)
    if ok then
        package.preload[name] = function() return value end
    else
        local msg = tostring(value)
        package.preload[name] = function() error(msg, 0) end
        log("load_now module " .. tostring(name) .. " failed: " .. msg)
    end
end

shim.manifest = manifest
log(("%d mod(s), %d module(s); mod_dir=%s"):format(
    #(manifest.mods or {}), #(manifest.modules or {}), tostring(manifest.mod_dir)))

return shim
