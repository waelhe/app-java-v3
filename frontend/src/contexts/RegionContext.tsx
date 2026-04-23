'use client';

import React, { createContext, useContext, useState, ReactNode } from 'react';

export type Region = 'qudsaya-center' | 'qudsaya-dahia';

interface RegionContextType {
  region: Region;
  setRegion: (region: Region) => void;
  regionName: string;
  regionNameEn: string;
}

const RegionContext = createContext<RegionContextType | undefined>(undefined);

export function RegionProvider({ children }: { children: ReactNode }) {
  const [region, setRegion] = useState<Region>('qudsaya-center');

  const regionNames = {
    'qudsaya-center': { ar: 'قدسيا', en: 'Qudsaya' },
    'qudsaya-dahia': { ar: 'ضاحية قدسيا', en: 'Qudsaya Dahia' }
  };

  const value: RegionContextType = {
    region,
    setRegion,
    regionName: regionNames[region].ar,
    regionNameEn: regionNames[region].en
  };

  return (
    <RegionContext.Provider value={value}>
      {children}
    </RegionContext.Provider>
  );
}

export function useRegion() {
  const context = useContext(RegionContext);
  if (context === undefined) {
    throw new Error('useRegion must be used within a RegionProvider');
  }
  return context;
}
