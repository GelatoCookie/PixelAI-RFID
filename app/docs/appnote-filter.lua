-- PDF layout filter for the app-note documents (README.md, app/docs/design.md).
-- Mermaid sources are dropped because each is followed by its pre-rendered JPEG.

function CodeBlock(el)
  if el.classes:includes('mermaid') then return {} end
  -- Unlabelled blocks otherwise become bare verbatim, skipping the shaded, line-wrapping style.
  if #el.classes == 0 then
    el.classes:insert('default')
    return el
  end
end

function HorizontalRule()
  return {}
end

-- The title page already carries the document title; emoji have no glyphs in the PDF fonts.
function Header(el)
  if el.level == 1 then return {} end
  while #el.content > 0 do
    local first = el.content[1]
    if first.t == 'Str' and not first.text:find('[A-Za-z0-9]') then
      el.content:remove(1)
    elseif first.t == 'Space' then
      el.content:remove(1)
    else
      break
    end
  end
  return el
end

local function image_row(images, captions)
  local width = string.format('%.2f', 0.96 / #images)
  local row = {}
  for i, img in ipairs(images) do
    table.insert(row, pandoc.RawInline('latex', '\\begin{minipage}[t]{' .. width .. '\\linewidth}\\centering '))
    table.insert(row, img)
    table.insert(row, pandoc.RawInline('latex', '\\caption{'))
    for _, c in ipairs(captions[i]) do table.insert(row, c) end
    table.insert(row, pandoc.RawInline('latex', '}\\end{minipage}' .. (i < #images and '\\hfill' or '')))
  end
  return {
    pandoc.RawBlock('latex', '\\begin{figure}[H]\\centering'),
    pandoc.Plain(row),
    pandoc.RawBlock('latex', '\\end{figure}'),
  }
end

-- A paragraph holding several images becomes one row of captioned figures.
function Para(el)
  local images, captions = {}, {}
  for _, inline in ipairs(el.content) do
    if inline.t == 'Image' then
      table.insert(images, inline)
      table.insert(captions, inline.caption)
    elseif inline.t ~= 'Space' and inline.t ~= 'SoftBreak' then
      return nil
    end
  end
  if #images < 2 then return nil end
  return image_row(images, captions)
end

-- A one-row table of images (GitHub gallery idiom) becomes a figure row captioned by its headers.
function Table(el)
  if #el.bodies ~= 1 or #el.bodies[1].body ~= 1 or #el.head.rows ~= 1 then return nil end
  local images, captions = {}, {}
  for i, cell in ipairs(el.bodies[1].body[1].cells) do
    local block = cell.contents[1]
    if #cell.contents ~= 1 or (block.t ~= 'Plain' and block.t ~= 'Para')
        or #block.content ~= 1 or block.content[1].t ~= 'Image' then
      return nil
    end
    table.insert(images, block.content[1])
    table.insert(captions, pandoc.utils.blocks_to_inlines(el.head.rows[1].cells[i].contents))
  end
  if #images < 2 then return nil end
  return image_row(images, captions)
end
