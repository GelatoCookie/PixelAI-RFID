-- PDF layout filter for design.md.
-- Mermaid sources are dropped because each is followed by its pre-rendered JPEG.

function CodeBlock(el)
  if el.classes:includes('mermaid') then return {} end
end

function HorizontalRule()
  return {}
end

-- The title page already carries the document title.
function Header(el)
  if el.level == 1 then return {} end
end

-- A paragraph holding several images becomes one row of captioned figures.
function Para(el)
  local images = {}
  for _, inline in ipairs(el.content) do
    if inline.t == 'Image' then
      table.insert(images, inline)
    elseif inline.t ~= 'Space' and inline.t ~= 'SoftBreak' then
      return nil
    end
  end
  if #images < 2 then return nil end

  local width = string.format('%.2f', 0.96 / #images)
  local row = {}
  for i, img in ipairs(images) do
    table.insert(row, pandoc.RawInline('latex', '\\begin{minipage}[t]{' .. width .. '\\linewidth}\\centering '))
    table.insert(row, img)
    table.insert(row, pandoc.RawInline('latex', '\\caption{'))
    for _, c in ipairs(img.caption) do table.insert(row, c) end
    table.insert(row, pandoc.RawInline('latex', '}\\end{minipage}' .. (i < #images and '\\hfill' or '')))
  end
  return {
    pandoc.RawBlock('latex', '\\begin{figure}[H]\\centering'),
    pandoc.Plain(row),
    pandoc.RawBlock('latex', '\\end{figure}'),
  }
end
