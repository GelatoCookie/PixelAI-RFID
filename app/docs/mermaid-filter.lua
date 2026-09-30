-- Renders fenced ```mermaid blocks to vector PDF figures with mermaid-cli (mmdc).
-- An optional "%% caption: ..." line inside the diagram becomes the figure caption.
-- Falls back to the source block if mmdc is unavailable or rendering fails.

local outdir = pandoc.system.get_working_directory() .. '/build/mermaid'
local config = pandoc.system.get_working_directory() .. '/app/docs/mermaid-config.json'

local function exists(path)
  local fh = io.open(path, 'r')
  if fh then fh:close() return true end
  return false
end

local function read(path)
  local fh = io.open(path, 'r')
  if not fh then return '' end
  local text = fh:read('a')
  fh:close()
  return text
end

local config_text = read(config)

function CodeBlock(el)
  if not el.classes:includes('mermaid') then return nil end

  -- Content-hashed names let unchanged diagrams skip re-rendering.
  local base = outdir .. '/' .. pandoc.utils.sha1(config_text .. el.text)
  local mmd, pdf = base .. '.mmd', base .. '.pdf'
  if not exists(pdf) then
    os.execute('mkdir -p "' .. outdir .. '"')
    local fh = io.open(mmd, 'w')
    fh:write(el.text)
    fh:close()
    local cmd = string.format('mmdc -q -c "%s" -i "%s" -o "%s" >/dev/null 2>&1', config, mmd, pdf)
    if not os.execute(cmd) or not exists(pdf) then
      io.stderr:write('mermaid-filter: mmdc failed, keeping source block\n')
      return el
    end
  end

  local caption = {}
  for line in el.text:gmatch('[^\n]+') do
    local text = line:match('^%s*%%%%%s*caption:%s*(.-)%s*$')
    if text then
      caption = { pandoc.Plain(pandoc.Inlines(text)) }
      break
    end
  end
  return pandoc.Figure({ pandoc.Plain({ pandoc.Image({}, pdf) }) }, caption)
end

local function diagram_src(block)
  if not block or block.t ~= 'Figure' then return nil end
  local plain = block.content[1]
  local img = plain and plain.content and plain.content[1]
  if img and img.t == 'Image' and img.src:sub(1, #outdir) == outdir then
    return img.src
  end
  return nil
end

-- Keep a heading (and its optional intro paragraph) on the same page as the diagram below it.
function Pandoc(doc)
  local out = pandoc.Blocks({})
  for i, block in ipairs(doc.blocks) do
    if block.t == 'Header' then
      local nxt = doc.blocks[i + 1]
      local src = diagram_src(nxt) or (nxt and nxt.t == 'Para' and diagram_src(doc.blocks[i + 2]))
      if src then
        out:insert(pandoc.RawBlock('latex',
          '\\sbox0{\\pandocbounded{\\includegraphics{' .. src .. '}}}' ..
          '\\needspace{\\dimexpr\\ht0+\\dp0+7\\baselineskip\\relax}'))
      end
    end
    out:insert(block)
  end
  doc.blocks = out
  return doc
end
