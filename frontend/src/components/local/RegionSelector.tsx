'use client';

import React, { useState, useRef, useEffect } from 'react';
import { MapPin, Check, ChevronDown } from 'lucide-react';
import { useRegion, Region } from '@/contexts/RegionContext';
import { useLanguage } from '@/contexts/LanguageContext';

interface RegionSelectorProps {
  className?: string;
  variant?: 'default' | 'light' | 'compact' | 'mini';
}

export default function RegionSelector({ className = '', variant = 'default' }: RegionSelectorProps) {
  const { region, setRegion } = useRegion();
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const [isOpen, setIsOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const regions: { id: Region; name: string; nameEn: string; icon: string }[] = [
    { id: 'qudsaya-center', name: 'قدسيا', nameEn: 'Qudsaya', icon: '🏙️' },
    { id: 'qudsaya-dahia', name: 'ضاحية قدسيا', nameEn: 'Dahia', icon: '🌳' }
  ];

  const currentRegion = regions.find(r => r.id === region)!;

  // إغلاق القائمة عند الضغط خارجها
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleSelect = (regionId: Region) => {
    setRegion(regionId);
    setIsOpen(false);
  };

  // Mini variant - قائمة منسدلة صغيرة
  if (variant === 'mini') {
    return (
      <div ref={dropdownRef} className={`relative ${className}`}>
        <button
          onClick={() => setIsOpen(!isOpen)}
          className="flex items-center gap-1.5 px-2 py-1 bg-white rounded-lg border border-gray-200 hover:border-emerald-300 hover:shadow-sm transition-all"
        >
          <MapPin className="w-3.5 h-3.5 text-emerald-600" />
          <span className="text-xs font-bold text-gray-700">
            {isArabic ? currentRegion.name : currentRegion.nameEn}
          </span>
          <ChevronDown className={`w-3 h-3 text-gray-400 transition-transform ${isOpen ? 'rotate-180' : ''}`} />
        </button>

        {isOpen && (
          <div className="absolute top-full right-0 mt-1 bg-white rounded-lg border border-gray-200 shadow-lg overflow-hidden z-50 min-w-[140px]">
            {regions.map((r) => {
              const isActive = region === r.id;
              return (
                <button
                  key={r.id}
                  onClick={() => handleSelect(r.id)}
                  className={`
                    w-full flex items-center gap-2 px-3 py-2 transition-all
                    ${isActive ? 'bg-emerald-50 text-emerald-700' : 'text-gray-700 hover:bg-gray-50'}
                  `}
                >
                  <span className="text-sm">{r.icon}</span>
                  <span className="font-bold text-xs flex-1 text-right">
                    {isArabic ? r.name : r.nameEn}
                  </span>
                  {isActive && <Check className="w-3.5 h-3.5 text-emerald-600" />}
                </button>
              );
            })}
          </div>
        )}
      </div>
    );
  }

  // Compact variant - للشريط العلوي
  if (variant === 'compact') {
    return (
      <div ref={dropdownRef} className={`relative ${className}`}>
        <button
          onClick={() => setIsOpen(!isOpen)}
          className="flex items-center gap-2 px-3 py-1.5 bg-white/10 backdrop-blur-sm rounded-lg border border-white/20 hover:bg-white/20 transition-all"
        >
          <MapPin className="w-4 h-4 text-white" />
          <span className="text-sm font-bold text-white">
            {isArabic ? currentRegion.name : currentRegion.nameEn}
          </span>
          <ChevronDown className={`w-4 h-4 text-white/70 transition-transform ${isOpen ? 'rotate-180' : ''}`} />
        </button>

        {isOpen && (
          <div className="absolute top-full right-0 mt-1 bg-white rounded-lg border border-gray-200 shadow-lg overflow-hidden z-50 min-w-[160px]">
            {regions.map((r) => {
              const isActive = region === r.id;
              return (
                <button
                  key={r.id}
                  onClick={() => handleSelect(r.id)}
                  className={`
                    w-full flex items-center gap-2 px-3 py-2.5 transition-all
                    ${isActive ? 'bg-emerald-50 text-emerald-700' : 'text-gray-700 hover:bg-gray-50'}
                  `}
                >
                  <span className="text-base">{r.icon}</span>
                  <span className="font-bold text-sm flex-1 text-right">
                    {isArabic ? r.name : r.nameEn}
                  </span>
                  {isActive && <Check className="w-4 h-4 text-emerald-600" />}
                </button>
              );
            })}
          </div>
        )}
      </div>
    );
  }

  // Light variant - للاستخدام داخل الأقسام
  if (variant === 'light') {
    return (
      <div ref={dropdownRef} className={`relative ${className}`}>
        <button
          onClick={() => setIsOpen(!isOpen)}
          className="flex items-center gap-2 px-3 py-2 bg-white rounded-lg border border-gray-200 hover:border-emerald-300 hover:shadow-sm transition-all"
        >
          <MapPin className="w-4 h-4 text-emerald-600" />
          <span className="text-sm font-bold text-gray-700">
            {isArabic ? currentRegion.name : currentRegion.nameEn}
          </span>
          <ChevronDown className={`w-4 h-4 text-gray-400 transition-transform ${isOpen ? 'rotate-180' : ''}`} />
        </button>

        {isOpen && (
          <div className="absolute top-full right-0 mt-1 bg-white rounded-lg border border-gray-200 shadow-lg overflow-hidden z-50 min-w-[160px]">
            {regions.map((r) => {
              const isActive = region === r.id;
              return (
                <button
                  key={r.id}
                  onClick={() => handleSelect(r.id)}
                  className={`
                    w-full flex items-center gap-2 px-3 py-2.5 transition-all
                    ${isActive ? 'bg-emerald-50 text-emerald-700' : 'text-gray-700 hover:bg-gray-50'}
                  `}
                >
                  <span className="text-base">{r.icon}</span>
                  <span className="font-bold text-sm flex-1 text-right">
                    {isArabic ? r.name : r.nameEn}
                  </span>
                  {isActive && <Check className="w-4 h-4 text-emerald-600" />}
                </button>
              );
            })}
          </div>
        )}
      </div>
    );
  }

  // Default - mini
  return (
    <RegionSelector variant="mini" className={className} />
  );
}
