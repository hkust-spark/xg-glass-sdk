-- Universal Glasses (ug) frameside app
--
-- Responsibilities:
-- - Receive host->Frame messages via data.min accumulator
-- - Handle capture requests using camera.min (sends photo chunks back using 0x07/0x08)
-- - Handle plain text display requests using plain_text.min
-- - Emit tap events as 0x09 raw packets (for RxTap)

local data = require('data.min')
local camera = require('camera.min')
local plain_text = require('plain_text.min')
local audio = require('audio.min')

-- Host -> Frame message codes (must match Flutter module)
local HOST_CAPTURE_SETTINGS = 0x20
local HOST_PLAIN_TEXT = 0x21
local HOST_MIC_CONTROL = 0x22

-- Frame -> host tap flag (matches RxTap default)
local TAP_FLAG = 0x09

data.parsers[HOST_CAPTURE_SETTINGS] = camera.parse_capture_settings
data.parsers[HOST_PLAIN_TEXT] = plain_text.parse_plain_text

-- Host -> Frame mic control payload:
-- [enable:1][rateCode:0|1][bitDepth:8|16]
local function parse_mic_control(block)
  local msg = {}
  msg.enable = (string.byte(block, 1) or 0) > 0
  local rateCode = string.byte(block, 2) or 0
  msg.sample_rate = (rateCode == 1) and 16000 or 8000
  local depth = string.byte(block, 3) or 8
  msg.bit_depth = (depth == 16) and 16 or 8
  return msg
end

data.parsers[HOST_MIC_CONTROL] = parse_mic_control

-- Send a single-byte tap flag for each tap. Host-side RxTap aggregates multi-taps.
frame.imu.tap_callback(function()
  pcall(frame.bluetooth.send, string.char(TAP_FLAG))
end)

local function clear_display()
  -- "Clear" hack used by frame_ble clearDisplay()
  frame.display.bitmap(1, 1, 4, 2, 15, string.char(0xFF))
end

local function render_plain_text(msg)
  clear_display()

  local x = msg.x or 1
  local y = msg.y or 1
  local spacing = msg.spacing or 4
  local color = msg.color or 'WHITE'
  local lineHeight = 18

  -- Render multi-line by splitting on \n
  for line in (msg.string .. "\n"):gmatch("(.-)\n") do
    frame.display.text(line, x, y, {color=color, spacing=spacing})
    y = y + lineHeight
    if y > 400 then
      break
    end
  end

  frame.display.show()
end

function app_loop()
  print('ug_frame_app running')

  local mic_started = false

  while true do
    rc, err = pcall(function()
      local items_ready = data.process_raw_items()
      if items_ready > 0 then
        if data.app_data[HOST_CAPTURE_SETTINGS] ~= nil then
          local settings = data.app_data[HOST_CAPTURE_SETTINGS]
          data.app_data[HOST_CAPTURE_SETTINGS] = nil
          camera.capture_and_send(settings)
          collectgarbage('collect')
        end

        if data.app_data[HOST_PLAIN_TEXT] ~= nil then
          local msg = data.app_data[HOST_PLAIN_TEXT]
          data.app_data[HOST_PLAIN_TEXT] = nil
          render_plain_text(msg)
          collectgarbage('collect')
        end

        if data.app_data[HOST_MIC_CONTROL] ~= nil then
          local cfg = data.app_data[HOST_MIC_CONTROL]
          data.app_data[HOST_MIC_CONTROL] = nil
          if cfg.enable then
            if mic_started == false then
              audio.start{sample_rate=cfg.sample_rate, bit_depth=cfg.bit_depth}
              mic_started = true
            end
          else
            -- Stop will trigger a final 0x06 packet once the buffer drains.
            if mic_started == true then
              audio.stop()
            end
          end
        end
      end

      -- If mic is started, keep pumping audio out to host.
      if mic_started == true then
        local sent = audio.read_and_send_audio()
        if sent == nil then
          -- End-of-stream observed after stop and buffer drain.
          mic_started = false
        end
      end

      frame.sleep(0.001)
    end)

    if rc == false then
      print(err)
      frame.display.text(' ', 1, 1)
      frame.display.show()
      break
    end
  end
end

app_loop()
