'use client';

import React, { useRef, useState, useEffect } from 'react';
import { Pill, Star, Heart, ChevronLeft, ChevronRight, Phone } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface Pharmacy {
  id: string;
  name: string;
  nameEn: string;
  address: string;
  addressEn: string;
  phone: string;
  hours: string;
  isOpen: boolean;
  rating: number;
  image: string;
  is24h?: boolean;
  hasDelivery?: boolean;
}

const qudsayaCenterPharmacies: Pharmacy[] = [
  {
    id: '1',
    name: 'صيدلية الشفاء',
    nameEn: 'Al-Shifa Pharmacy',
    address: 'الساحة الرئيسية - قدسيا المركز',
    addressEn: 'Main Square - Qudsaya Center',
    phone: '0999123456',
    hours: '24 ساعة',
    isOpen: true,
    rating: 4.8,
    image: 'https://images.unsplash.com/photo-1576602976047-174e57a47881?auto=format&fit=crop&w=400&q=80',
    is24h: true,
    hasDelivery: true
  },
  {
    id: '2',
    name: 'صيدلية النور',
    nameEn: 'Al-Noor Pharmacy',
    address: 'شارع المدرسة - قدسيا المركز',
    addressEn: 'School Street - Qudsaya Center',
    phone: '0998765432',
    hours: '8 ص - 10 م',
    isOpen: true,
    rating: 4.6,
    image: 'https://images.unsplash.com/photo-1587854692152-cbe660dbde88?auto=format&fit=crop&w=400&q=80',
    hasDelivery: true
  },
  {
    id: '3',
    name: 'صيدلية الرحمة',
    nameEn: 'Al-Rahma Pharmacy',
    address: 'الحي الغربي - قدسيا المركز',
    addressEn: 'West District - Qudsaya Center',
    phone: '0998111222',
    hours: '24 ساعة',
    isOpen: true,
    rating: 4.7,
    image: 'https://images.unsplash.com/photo-1631549916768-4119b2e5f926?auto=format&fit=crop&w=400&q=80',
    is24h: true
  },
  {
    id: '4',
    name: 'صيدلية القدس',
    nameEn: 'Al-Quds Pharmacy',
    address: 'نزلة قدسيا',
    addressEn: 'Qudsaya Slope',
    phone: '0999333444',
    hours: '9 ص - 9 م',
    isOpen: true,
    rating: 4.5,
    image: 'https://images.unsplash.com/photo-1576602976047-174e57a47881?auto=format&fit=crop&w=400&q=80'
  }
];

const qudsayaDahiaPharmacies: Pharmacy[] = [
  {
    id: '1',
    name: 'صيدلية الضاحية',
    nameEn: 'Dahia Pharmacy',
    address: 'الساحة الرئيسية - ضاحية قدسيا',
    addressEn: 'Main Square - Qudsaya Dahia',
    phone: '0999555666',
    hours: '24 ساعة',
    isOpen: true,
    rating: 4.9,
    image: 'https://images.unsplash.com/photo-1576602976047-174e57a47881?auto=format&fit=crop&w=400&q=80',
    is24h: true,
    hasDelivery: true
  },
  {
    id: '2',
    name: 'صيدلية الأمل',
    nameEn: 'Al-Amal Pharmacy',
    address: 'شارع الرئيسي - ضاحية قدسيا',
    addressEn: 'Main Street - Qudsaya Dahia',
    phone: '0999777888',
    hours: '8 ص - 12 ص',
    isOpen: true,
    rating: 4.7,
    image: 'https://images.unsplash.com/photo-1587854692152-cbe660dbde88?auto=format&fit=crop&w=400&q=80',
    is24h: true
  },
  {
    id: '3',
    name: 'صيدلية الصحة',
    nameEn: 'Al-Seha Pharmacy',
    address: 'الحي الجنوبي - ضاحية قدسيا',
    addressEn: 'South District - Qudsaya Dahia',
    phone: '0999000111',
    hours: '9 ص - 10 م',
    isOpen: true,
    rating: 4.5,
    image: 'https://images.unsplash.com/photo-1631549916768-4119b2e5f926?auto=format&fit=crop&w=400&q=80',
    hasDelivery: true
  }
];

const pharmaciesByRegion: Record<Region, Pharmacy[]> = {
  'qudsaya-center': qudsayaCenterPharmacies,
  'qudsaya-dahia': qudsayaDahiaPharmacies
};

export default function Pharmacies() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const pharmacies = pharmaciesByRegion[region];

  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [favorites, setFavorites] = useState<string[]>([]);
  const [showAll, setShowAll] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  const checkScroll = () => {
    if (scrollRef.current) {
      const { scrollLeft, scrollWidth, clientWidth } = scrollRef.current;
      setCanScrollLeft(scrollLeft > 0);
      setCanScrollRight(scrollLeft < scrollWidth - clientWidth - 10);
    }
  };

  useEffect(() => {
    checkScroll();
    const scrollEl = scrollRef.current;
    if (scrollEl) {
      scrollEl.addEventListener('scroll', checkScroll);
      return () => scrollEl.removeEventListener('scroll', checkScroll);
    }
  }, [pharmacies]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({
        left: direction === 'left' ? -280 : 280,
        behavior: 'smooth'
      });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev =>
      prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]
    );
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '💊 صيدليات' : '💊 Pharmacies'}
        </h2>

        {/* Horizontal Scrolling Container - Airbnb Style */}
        <div className="relative">
          {/* Navigation Buttons */}
          {canScrollLeft && (
            <button
              onClick={() => scroll('left')}
              className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform"
            >
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button
              onClick={() => scroll('right')}
              className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform"
            >
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          {/* Scrollable Cards */}
          <div
            ref={scrollRef}
            className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide"
            style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}
          >
            {pharmacies.slice(0, 8).map((pharmacy) => {
              const isFavorite = favorites.includes(pharmacy.id);

              return (
                <div
                  key={pharmacy.id}
                  className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
                >
                  {/* Image Container - Airbnb Style */}
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img
                      src={pharmacy.image}
                      alt=""
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                    />
                    
                    {/* Favorite Button */}
                    <button
                      onClick={(e) => { e.stopPropagation(); toggleFavorite(pharmacy.id); }}
                      className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110"
                    >
                      <Heart
                        className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`}
                      />
                    </button>

                    {/* Status Badge */}
                    <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${
                      pharmacy.isOpen ? 'bg-emerald-500/90 text-white' : 'bg-gray-500/90 text-white'
                    }`}>
                      {pharmacy.isOpen ? (isArabic ? 'مفتوح' : 'Open') : (isArabic ? 'مغلق' : 'Closed')}
                    </span>

                    {/* 24h Badge */}
                    {pharmacy.is24h && (
                      <span className="absolute top-2 right-10 px-2 py-0.5 bg-amber-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        24H
                      </span>
                    )}

                    {/* Delivery Badge */}
                    {pharmacy.hasDelivery && (
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-blue-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        {isArabic ? 'توصيل' : 'Delivery'}
                      </span>
                    )}
                  </div>

                  {/* Pharmacy Info - Airbnb Style */}
                  <div>
                    {/* Name */}
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">
                      {isArabic ? pharmacy.name : pharmacy.nameEn}
                    </h3>

                    {/* Address */}
                    <p className="text-xs text-gray-500 mb-1 truncate">
                      📍 {isArabic ? pharmacy.address : pharmacy.addressEn}
                    </p>

                    {/* Rating & Phone */}
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-0.5">
                        <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                        <span className="text-xs font-medium">{pharmacy.rating}</span>
                      </div>
                      <a
                        href={`tel:${pharmacy.phone}`}
                        onClick={(e) => e.stopPropagation()}
                        className="text-xs text-emerald-600 hover:underline"
                      >
                        📞 {pharmacy.phone}
                      </a>
                    </div>
                  </div>
                </div>
              );
            })}
            
            {/* View All Card */}
            <button
              onClick={() => setShowAll(true)}
              className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
            >
              <div className="aspect-square rounded-xl overflow-hidden relative bg-gray-100">
                <div className="grid grid-cols-2 grid-rows-2 h-full gap-0.5">
                  {pharmacies.slice(0, 4).map((p, i) => (
                    <div key={i} className="overflow-hidden">
                      <img src={p.image} alt="" className="w-full h-full object-cover" />
                    </div>
                  ))}
                </div>
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">{isArabic ? 'عرض الكل' : 'Show all'}</span>
                  <span className="text-xs text-white/80 mt-0.5">{pharmacies.length} {isArabic ? 'صيدلية' : 'pharmacies'}</span>
                </div>
              </div>
            </button>
          </div>
        </div>
      </div>

      {/* All Items Drawer */}
      <Drawer.Root open={showAll} onOpenChange={setShowAll}>
        <Drawer.Content className="fixed inset-x-0 bottom-0 h-[90vh] bg-white rounded-t-3xl shadow-2xl z-50">
          <div className="px-4 pt-4 pb-3 border-b border-gray-100">
            <Drawer.Handle className="w-10 h-1 bg-gray-300 rounded-full mx-auto mb-3 block" />
            <Drawer.Title className="text-lg font-bold text-right">
              {isArabic ? 'جميع الصيدليات' : 'All Pharmacies'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {pharmacies.length} {isArabic ? 'صيدلية متاحة' : 'pharmacies available'}
            </p>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {pharmacies.map((pharmacy) => {
                const isFavorite = favorites.includes(pharmacy.id);
                return (
                  <div key={pharmacy.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={pharmacy.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(pharmacy.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-semibold rounded-full backdrop-blur-sm ${pharmacy.isOpen ? 'bg-emerald-500/90 text-white' : 'bg-gray-500/90 text-white'}`}>
                        {pharmacy.isOpen ? (isArabic ? 'مفتوح' : 'Open') : (isArabic ? 'مغلق' : 'Closed')}
                      </span>
                      {pharmacy.is24h && (
                        <span className="absolute top-2 right-10 px-2 py-0.5 bg-amber-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">24H</span>
                      )}
                      {pharmacy.hasDelivery && (
                        <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-blue-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                          {isArabic ? 'توصيل' : 'Delivery'}
                        </span>
                      )}
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? pharmacy.name : pharmacy.nameEn}</h3>
                    <p className="text-xs text-gray-500 truncate">📍 {isArabic ? pharmacy.address : pharmacy.addressEn}</p>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-0.5">
                        <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                        <span className="text-xs font-medium">{pharmacy.rating}</span>
                      </div>
                      <a href={`tel:${pharmacy.phone}`} onClick={(e) => e.stopPropagation()} className="text-xs text-emerald-600 hover:underline">📞</a>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </Drawer.Content>
      </Drawer.Root>
    </section>
  );
}
