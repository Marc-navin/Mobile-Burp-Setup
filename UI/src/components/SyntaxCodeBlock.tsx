import React from 'react';

interface SyntaxCodeBlockProps {
  code: string;
  mode?: 'json' | 'http' | 'hex' | 'plain';
  editable?: boolean;
  onChange?: (val: string) => void;
  maxHeightClass?: string;
}

function renderJsonLine(line: string): React.ReactNode {
  // Highlight JSON keys, string values, booleans, numbers cleanly
  const keyValMatch = line.match(/^(\s*)"([^"]+)"(\s*:\s*)(.*)$/);
  if (keyValMatch) {
    const [, indent, key, colon, rest] = keyValMatch;
    const isStringVal = rest.startsWith('"');
    const isBoolOrNum = /^(true|false|null|\d+)/.test(rest);
    return (
      <>
        <span>{indent}</span>
        <span className="text-[#4cd7f6]">"{key}"</span>
        <span className="text-[#bcc9cd]">{colon}</span>
        <span
          className={
            isStringVal
              ? 'text-[#4edea3]'
              : isBoolOrNum
                ? 'text-[#ffb95f]'
                : 'text-[#e0e2ea]'
          }
        >
          {rest}
        </span>
      </>
    );
  }
  return <span className="text-[#bcc9cd]">{line}</span>;
}

function renderHttpLine(line: string, idx: number): React.ReactNode {
  if (idx === 0) {
    const parts = line.split(' ');
    if (parts.length >= 3) {
      return (
        <>
          <span className="text-[#ffb95f] font-semibold">{parts[0]}</span>{' '}
          <span className="text-[#4cd7f6] font-medium">{parts[1]}</span>{' '}
          <span className="text-[#869397]">{parts.slice(2).join(' ')}</span>
        </>
      );
    }
  }
  const colonIdx = line.indexOf(':');
  if (colonIdx > 0 && !line.trim().startsWith('{') && !line.trim().startsWith('"')) {
    const headerName = line.slice(0, colonIdx + 1);
    const headerVal = line.slice(colonIdx + 1);
    return (
      <>
        <span className="text-[#06b6d4] font-medium">{headerName}</span>
        <span className="text-[#bcc9cd]">{headerVal}</span>
      </>
    );
  }
  return renderJsonLine(line);
}

export function toHexDump(input: string, maxLines = 6): Array<{ offset: string; hex: string; ascii: string }> {
  const encoder = new TextEncoder();
  const bytes = encoder.encode(input || '{}');
  const rows: Array<{ offset: string; hex: string; ascii: string }> = [];
  for (let i = 0; i < Math.min(bytes.length, maxLines * 16); i += 16) {
    const slice = bytes.slice(i, i + 16);
    const hexParts: string[] = [];
    let ascii = '';
    for (let j = 0; j < 16; j++) {
      if (j < slice.length) {
        const b = slice[j];
        hexParts.push(b.toString(16).padStart(2, '0'));
        ascii += b >= 32 && b <= 126 ? String.fromCharCode(b) : '.';
      } else {
        hexParts.push('  ');
      }
    }
    const groupedHex = `${hexParts.slice(0, 4).join(' ')}  ${hexParts.slice(4, 8).join(' ')}  ${hexParts.slice(8, 12).join(' ')}  ${hexParts.slice(12, 16).join(' ')}`;
    rows.push({
      offset: `${i.toString(16).padStart(8, '0')}:`,
      hex: groupedHex,
      ascii,
    });
  }
  return rows;
}

export const SyntaxCodeBlock: React.FC<SyntaxCodeBlockProps> = ({
  code,
  mode = 'json',
  editable = false,
  onChange,
  maxHeightClass = '',
}) => {
  if (editable && onChange) {
    return (
      <div className={`rounded-lg bg-[#0a0e13] border border-[#3d494c]/40 p-3 ${maxHeightClass}`}>
        <textarea
          value={code}
          onChange={(e) => onChange(e.target.value)}
          spellCheck={false}
          className="w-full min-h-[160px] bg-transparent font-mono text-[12px] leading-[19px] text-[#e0e2ea] focus:outline-none resize-y"
          aria-label="Payload editor"
        />
      </div>
    );
  }

  if (mode === 'hex') {
    const rows = toHexDump(code, 10);
    return (
      <div className={`rounded-lg bg-[#0a0e13] p-3 font-mono text-[11px] leading-[18px] overflow-x-auto ${maxHeightClass}`}>
        {rows.map((r) => (
          <div key={r.offset} className="whitespace-pre flex gap-3">
            <span className="text-[#869397] select-none">{r.offset}</span>
            <span className="text-[#bcc9cd]">{r.hex}</span>
            <span className="text-[#e0e2ea]">{r.ascii}</span>
          </div>
        ))}
      </div>
    );
  }

  const lines = (code || '').split('\n');

  return (
    <div className={`rounded-lg bg-[#0a0e13] p-3 flex gap-3 overflow-x-auto font-mono text-[12px] leading-[19px] ${maxHeightClass}`}>
      <div className="flex flex-col text-right text-[#869397]/60 select-none pr-2 border-r border-[#262a30] shrink-0 tabular-nums">
        {lines.map((_, i) => (
          <span key={i}>{String(i + 1).padStart(2, '0')}</span>
        ))}
      </div>
      <div className="flex flex-col flex-1 min-w-0 whitespace-pre">
        {lines.map((line, i) => (
          <div key={i} className="min-h-[19px]">
            {mode === 'http' ? renderHttpLine(line, i) : renderJsonLine(line)}
          </div>
        ))}
      </div>
    </div>
  );
};
