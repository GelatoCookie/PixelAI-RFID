-- Renders fenced ```mermaid code blocks to vector PDF via mermaid-cli (mmdc)
-- and replaces them with a centered, width-capped image.
-- Requires: mmdc on PATH; adjustbox (export option) loaded by the LaTeX template.

local outdir = 'docs/_mermaid'
local counter = 0

local function is_mermaid(el)
  for _, c in ipairs(el.classes) do
    if c == 'mermaid' then return true end
  end
  return false
end

function CodeBlock(el)
  if not is_mermaid(el) then return nil end
  counter = counter + 1
  os.execute('mkdir -p ' .. outdir)
  local base = outdir .. '/diagram-' .. counter
  local mmd = base .. '.mmd'
  local pdf = base .. '.pdf'
  local fh = io.open(mmd, 'w')
  fh:write(el.text)
  fh:close()
  local cmd = string.format('mmdc -i %s -o %s --pdfFit -b white >/dev/null 2>&1', mmd, pdf)
  local ok = os.execute(cmd)
  if not ok then
    return el -- fall back to the source block if rendering fails
  end
  local latex = '\\begin{center}\\includegraphics[max width=\\linewidth,max height=0.72\\textheight,keepaspectratio]{' .. pdf .. '}\\end{center}'
  return pandoc.RawBlock('latex', latex)
end
