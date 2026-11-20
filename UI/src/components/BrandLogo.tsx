import React, { useState } from 'react';
import { Shield } from 'lucide-react';
import { BRAND_LOGO_URL } from '../data/mockTraffic';

interface BrandLogoProps {
  className?: string;
}

export const BrandLogo: React.FC<BrandLogoProps> = ({ className = 'w-8 h-8' }) => {
  const [failed, setFailed] = useState(false);

  if (failed) {
    return (
      <div
        className={`${className} rounded-lg bg-[#1c2025] border border-[#3d494c]/60 flex items-center justify-center text-[#4cd7f6] shrink-0`}
        aria-label="NetProxy Security Logo"
      >
        <Shield className="w-4 h-4" />
      </div>
    );
  }

  return (
    <img
      src={BRAND_LOGO_URL}
      alt="NetProxy Security"
      referrerPolicy="no-referrer"
      onError={() => setFailed(true)}
      className={`${className} rounded-md object-contain bg-[#181c21] shrink-0`}
    />
  );
};
