'use client';

import React, { useState, useRef, useEffect } from 'react';
import {
  Scale, HardHat, Calculator, Briefcase, Palette, Camera,
  Star, Heart, ChevronLeft, ChevronRight, Award, Phone, Stethoscope
} from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer } from 'vaul';

interface Professional {
  id: string;
  name: string;
  nameEn: string;
  profession: string;
  professionEn: string;
  category: 'lawyer' | 'engineer' | 'accountant' | 'consultant' | 'designer' | 'photographer' | 'doctor';
  specialty: string;
  specialtyEn: string;
  image: string;
  location: string;
  phone: string;
  experience: string;
  rating: number;
  reviews: number;
  verified?: boolean;
  featured?: boolean;
  isAvailable?: boolean;
  nextAvailable?: string;
}

const qudsayaCenterProfessionals: Professional[] = [
  // المحامين
  { id: 'l1', name: 'المحامي أحمد الخالد', nameEn: 'Lawyer Ahmed Al-Khaled', profession: 'محامي', professionEn: 'Lawyer', category: 'lawyer', specialty: 'قانون مدني', specialtyEn: 'Civil Law', image: 'https://images.unsplash.com/photo-1589829545856-d10d557cf95f?auto=format&fit=crop&w=400&q=80', location: 'قدسيا - برج القاضي', phone: '0999111001', experience: '15 سنة', rating: 4.9, reviews: 85, verified: true, featured: true },
  { id: 'l2', name: 'المحامية سارة المنصور', nameEn: 'Lawyer Sara Al-Mansour', profession: 'محامية', professionEn: 'Lawyer', category: 'lawyer', specialty: 'قانون عقاري', specialtyEn: 'Real Estate Law', image: 'https://images.unsplash.com/photo-1573496359142-b8d87734a5a2?auto=format&fit=crop&w=400&q=80', location: 'قدسيا - الساحة', phone: '0999111002', experience: '12 سنة', rating: 4.8, reviews: 72, verified: true },
  // المهندسين
  { id: 'e1', name: 'المهندس فادي السيد', nameEn: 'Eng. Fadi Al-Sayed', profession: 'مهندس مدني', professionEn: 'Civil Engineer', category: 'engineer', specialty: 'إنشاءات', specialtyEn: 'Construction', image: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?auto=format&fit=crop&w=400&q=80', location: 'قدسيا - مكتب المهندسين', phone: '0999222001', experience: '18 سنة', rating: 4.9, reviews: 110, verified: true, featured: true },
  // المحاسبين
  { id: 'a1', name: 'المحاسب نزار الخطيب', nameEn: 'Accountant Nizar Al-Khatib', profession: 'محاسب قانوني', professionEn: 'CPA', category: 'accountant', specialty: 'محاسبة وضرائب', specialtyEn: 'Accounting & Tax', image: 'https://images.unsplash.com/photo-1554224155-6726b3ff858f?auto=format&fit=crop&w=400&q=80', location: 'قدسيا - برج الأعمال', phone: '0999333001', experience: '20 سنة', rating: 4.9, reviews: 95, verified: true, featured: true },
  // الاستشاريين
  { id: 'c1', name: 'الاستشاري سمير ناصر', nameEn: 'Consultant Samir Nasser', profession: 'مستشار إداري', professionEn: 'Management Consultant', category: 'consultant', specialty: 'إدارة أعمال', specialtyEn: 'Business Management', image: 'https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?auto=format&fit=crop&w=400&q=80', location: 'قدسيا - برج الأعمال', phone: '0999444001', experience: '16 سنة', rating: 4.8, reviews: 78, verified: true },
  // المصممين
  { id: 'd1', name: 'المصمم هاني زين', nameEn: 'Designer Hani Zein', profession: 'مصمم جرافيك', professionEn: 'Graphic Designer', category: 'designer', specialty: 'هوية بصرية', specialtyEn: 'Visual Identity', image: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?auto=format&fit=crop&w=400&q=80', location: 'قدسيا - مكتب التصميم', phone: '0999555001', experience: '7 سنوات', rating: 4.6, reviews: 48, featured: true },
  // المصورين
  { id: 'p1', name: 'المصور سامر العلي', nameEn: 'Photographer Samer Al-Ali', profession: 'مصور أعراس', professionEn: 'Wedding Photographer', category: 'photographer', specialty: 'تصوير أعراس', specialtyEn: 'Wedding Photography', image: 'https://images.unsplash.com/photo-1516035069371-29a1b244cc32?auto=format&fit=crop&w=400&q=80', location: 'قدسيا - استوديو سامر', phone: '0999666001', experience: '8 سنوات', rating: 4.9, reviews: 120, verified: true, featured: true },
  // الأطباء
  { id: 'dr1', name: 'د. محمود القدسي', nameEn: 'Dr. Mahmoud Al-Qudsi', profession: 'طبيب', professionEn: 'Doctor', category: 'doctor', specialty: 'طب عام', specialtyEn: 'General Medicine', image: 'https://images.unsplash.com/photo-1612349317150-e413f6a5b16d?auto=format&fit=crop&w=400&q=80', location: 'قدسيا - عيادة القدسي', phone: '0999777001', experience: '15 سنة', rating: 4.8, reviews: 134, verified: true, isAvailable: true, nextAvailable: '9:30 ص' },
  { id: 'dr2', name: 'د. نورة الأحمد', nameEn: 'Dr. Noura Al-Ahmad', profession: 'طبيبة أطفال', professionEn: 'Pediatrician', category: 'doctor', specialty: 'طب أطفال', specialtyEn: 'Pediatrics', image: 'https://images.unsplash.com/photo-1559839734-2b71ea197ec2?auto=format&fit=crop&w=400&q=80', location: 'قدسيا - عيادة الأطفال', phone: '0999777002', experience: '12 سنة', rating: 4.9, reviews: 167, verified: true, featured: true, isAvailable: true, nextAvailable: '10:00 ص' },
  { id: 'dr3', name: 'د. خالد المحمود', nameEn: 'Dr. Khaled Al-Mahmoud', profession: 'طبيب أسنان', professionEn: 'Dentist', category: 'doctor', specialty: 'طب أسنان', specialtyEn: 'Dentistry', image: 'https://images.unsplash.com/photo-1622253692010-333f2da6031d?auto=format&fit=crop&w=400&q=80', location: 'قدسيا - عيادة الأسنان', phone: '0999777003', experience: '10 سنوات', rating: 4.7, reviews: 89, isAvailable: true, nextAvailable: '1:00 م' },
  { id: 'dr4', name: 'د. رنا السيد', nameEn: 'Dr. Rana Al-Sayed', profession: 'طبيبة نسائية', professionEn: 'Gynecologist', category: 'doctor', specialty: 'طب نسائية', specialtyEn: 'Gynecology', image: 'https://images.unsplash.com/photo-1594824476967-48c8b964273f?auto=format&fit=crop&w=400&q=80', location: 'قدسيا - العيادة النسائية', phone: '0999777004', experience: '14 سنة', rating: 4.8, reviews: 145, verified: true, featured: true, isAvailable: true, nextAvailable: '3:30 م' },
  { id: 'dr5', name: 'د. فاطمة الزهراء', nameEn: 'Dr. Fatima Al-Zahra', profession: 'طبيبة جلدية', professionEn: 'Dermatologist', category: 'doctor', specialty: 'أمراض جلدية', specialtyEn: 'Dermatology', image: 'https://images.unsplash.com/photo-1651008376811-b90baee60c1f?auto=format&fit=crop&w=400&q=80', location: 'قدسيا - عيادة الجلدية', phone: '0999777005', experience: '8 سنوات', rating: 4.6, reviews: 76, isAvailable: true, nextAvailable: '11:00 ص' },
  { id: 'dr6', name: 'د. حسن الكردي', nameEn: 'Dr. Hassan Al-Kurdi', profession: 'طبيب قلب', professionEn: 'Cardiologist', category: 'doctor', specialty: 'طب قلب', specialtyEn: 'Cardiology', image: 'https://images.unsplash.com/photo-1537368910025-700350fe46c7?auto=format&fit=crop&w=400&q=80', location: 'قدسيا - عيادة القلب', phone: '0999777006', experience: '18 سنة', rating: 4.9, reviews: 198, verified: true, featured: true, isAvailable: true, nextAvailable: '2:30 م' },
];

const qudsayaDahiaProfessionals: Professional[] = [
  // المحامين
  { id: 'dl1', name: 'المحامي حسن الزهراوي', nameEn: 'Lawyer Hassan Al-Zahrawi', profession: 'محامي', professionEn: 'Lawyer', category: 'lawyer', specialty: 'قانون جنائي', specialtyEn: 'Criminal Law', image: 'https://images.unsplash.com/photo-1589829545856-d10d557cf95f?auto=format&fit=crop&w=400&q=80', location: 'الضاحية - مكتب المحامي', phone: '0999111004', experience: '13 سنة', rating: 4.8, reviews: 78, verified: true, featured: true },
  // المهندسين
  { id: 'de1', name: 'المهندس بلال حسن', nameEn: 'Eng. Bilal Hassan', profession: 'مهندس مدني', professionEn: 'Civil Engineer', category: 'engineer', specialty: 'ترميم وبناء', specialtyEn: 'Renovation & Building', image: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?auto=format&fit=crop&w=400&q=80', location: 'الضاحية - مكتب الهندسة', phone: '0999222004', experience: '16 سنة', rating: 4.8, reviews: 88, verified: true, featured: true },
  // المحاسبين
  { id: 'da1', name: 'المحاسب وليد سعيد', nameEn: 'Accountant Waleed Saeed', profession: 'محاسب قانوني', professionEn: 'CPA', category: 'accountant', specialty: 'محاسبة شركات', specialtyEn: 'Corporate Accounting', image: 'https://images.unsplash.com/photo-1554224155-6726b3ff858f?auto=format&fit=crop&w=400&q=80', location: 'الضاحية - برج الأعمال', phone: '0999333003', experience: '15 سنة', rating: 4.8, reviews: 82, verified: true },
  // المصممين
  { id: 'dd1', name: 'المصممة هدى منصور', nameEn: 'Designer Huda Mansour', profession: 'مصممة أزياء', professionEn: 'Fashion Designer', category: 'designer', specialty: 'أزياء نسائية', specialtyEn: 'Women\'s Fashion', image: 'https://images.unsplash.com/photo-1573496359142-b8d87734a5a2?auto=format&fit=crop&w=400&q=80', location: 'الضاحية - مشغل الأزياء', phone: '0999555003', experience: '6 سنوات', rating: 4.5, reviews: 38 },
  // الأطباء
  { id: 'ddr1', name: 'د. أحمد العمري', nameEn: 'Dr. Ahmed Al-Omari', profession: 'طبيب', professionEn: 'Doctor', category: 'doctor', specialty: 'طب عام', specialtyEn: 'General Medicine', image: 'https://images.unsplash.com/photo-1612349317150-e413f6a5b16d?auto=format&fit=crop&w=400&q=80', location: 'الضاحية - عيادة العمري', phone: '0999888001', experience: '15 سنة', rating: 4.9, reviews: 156, verified: true, featured: true, isAvailable: true, nextAvailable: '10:00 ص' },
  { id: 'ddr2', name: 'د. سارة الخالد', nameEn: 'Dr. Sara Al-Khaled', profession: 'طبيبة أطفال', professionEn: 'Pediatrician', category: 'doctor', specialty: 'طب أطفال', specialtyEn: 'Pediatrics', image: 'https://images.unsplash.com/photo-1559839734-2b71ea197ec2?auto=format&fit=crop&w=400&q=80', location: 'الضاحية - عيادة الأطفال', phone: '0999888002', experience: '10 سنوات', rating: 4.8, reviews: 98, isAvailable: true, nextAvailable: '11:30 ص' },
  { id: 'ddr3', name: 'د. محمد العلي', nameEn: 'Dr. Mohammad Al-Ali', profession: 'طبيب أسنان', professionEn: 'Dentist', category: 'doctor', specialty: 'طب أسنان', specialtyEn: 'Dentistry', image: 'https://images.unsplash.com/photo-1622253692010-333f2da6031d?auto=format&fit=crop&w=400&q=80', location: 'الضاحية - عيادة الأسنان', phone: '0999888003', experience: '8 سنوات', rating: 4.7, reviews: 124, isAvailable: true, nextAvailable: '2:00 م' },
  { id: 'ddr4', name: 'د. ليلى حسن', nameEn: 'Dr. Laila Hassan', profession: 'طبيبة جلدية', professionEn: 'Dermatologist', category: 'doctor', specialty: 'أمراض جلدية', specialtyEn: 'Dermatology', image: 'https://images.unsplash.com/photo-1594824476967-48c8b964273f?auto=format&fit=crop&w=400&q=80', location: 'الضاحية - عيادة الجلدية', phone: '0999888004', experience: '9 سنوات', rating: 4.6, reviews: 87, verified: true, featured: true, isAvailable: true, nextAvailable: '4:00 م' },
  { id: 'ddr5', name: 'د. خالد المحمود', nameEn: 'Dr. Khaled Al-Mahmoud', profession: 'طبيب قلب', professionEn: 'Cardiologist', category: 'doctor', specialty: 'طب قلب', specialtyEn: 'Cardiology', image: 'https://images.unsplash.com/photo-1537368910025-700350fe46c7?auto=format&fit=crop&w=400&q=80', location: 'الضاحية - عيادة القلب', phone: '0999888005', experience: '16 سنة', rating: 4.9, reviews: 201, verified: true, featured: true, isAvailable: true, nextAvailable: '9:00 ص' },
  { id: 'ddr6', name: 'د. نورا الأحمد', nameEn: 'Dr. Noura Al-Ahmad', profession: 'طبيبة نسائية', professionEn: 'Gynecologist', category: 'doctor', specialty: 'طب نسائية', specialtyEn: 'Gynecology', image: 'https://images.unsplash.com/photo-1651008376811-b90baee60c1f?auto=format&fit=crop&w=400&q=80', location: 'الضاحية - العيادة النسائية', phone: '0999888006', experience: '12 سنة', rating: 4.8, reviews: 178, isAvailable: true, nextAvailable: '1:30 م' },
];

const dataByRegion: Record<Region, Professional[]> = {
  'qudsaya-center': qudsayaCenterProfessionals,
  'qudsaya-dahia': qudsayaDahiaProfessionals
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Briefcase },
  { id: 'doctor', name: 'أطباء', nameEn: 'Doctors', icon: Stethoscope },
  { id: 'lawyer', name: 'محامين', nameEn: 'Lawyers', icon: Scale },
  { id: 'engineer', name: 'مهندسين', nameEn: 'Engineers', icon: HardHat },
  { id: 'accountant', name: 'محاسبين', nameEn: 'Accountants', icon: Calculator },
  { id: 'consultant', name: 'استشاريين', nameEn: 'Consultants', icon: Briefcase },
  { id: 'designer', name: 'مصممين', nameEn: 'Designers', icon: Palette },
  { id: 'photographer', name: 'مصورين', nameEn: 'Photographers', icon: Camera },
];

export default function Professionals() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  const professionals = dataByRegion[region];
  
  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [showAll, setShowAll] = useState(false);
  
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredProfessionals = professionals.filter(pro => activeCategory === 'all' || pro.category === activeCategory);

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
  }, [filteredProfessionals]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({ left: direction === 'left' ? -280 : 280, behavior: 'smooth' });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]);
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {categoryFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeCategory === filter.id;
            return (
              <button
                key={filter.id}
                onClick={() => setActiveCategory(filter.id)}
                className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
                  isActive ? 'border-gray-900 text-gray-900' : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-xs font-medium">{isArabic ? filter.name : filter.nameEn}</span>
              </button>
            );
          })}
        </div>

        {/* Horizontal Scrolling Container - Airbnb Style */}
        <div className="relative">
          {canScrollLeft && (
            <button onClick={() => scroll('left')} className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button onClick={() => scroll('right')} className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          <div ref={scrollRef} className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide" style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}>
            {filteredProfessionals.slice(0, 8).map((pro) => {
              const isFavorite = favorites.includes(pro.id);
              const isDoctor = pro.category === 'doctor';
              return (
                <div key={pro.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                    <img src={pro.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(pro.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    {pro.verified && (
                      <span className="absolute top-2 left-2 px-2 py-0.5 bg-blue-500/90 text-white text-[9px] font-semibold rounded-full backdrop-blur-sm flex items-center gap-0.5">
                        <Award className="w-2.5 h-2.5" /> {isArabic ? 'موثق' : 'Verified'}
                      </span>
                    )}
                    {isDoctor && pro.isAvailable && (
                      <span className="absolute top-2 left-2 px-2 py-0.5 bg-emerald-500/90 text-white text-[9px] font-semibold rounded-full backdrop-blur-sm">
                        {isArabic ? 'متاح' : 'Available'}
                      </span>
                    )}
                    {pro.featured && (
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-amber-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    )}
                    {isDoctor && pro.nextAvailable && (
                      <span className="absolute bottom-2 right-2 px-2 py-0.5 bg-white/90 text-gray-700 text-[9px] font-semibold rounded-full backdrop-blur-sm">
                        {pro.nextAvailable}
                      </span>
                    )}
                  </div>
                  <div>
                    <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${
                      isDoctor ? 'bg-emerald-100 text-emerald-700' : 'bg-violet-100 text-violet-700'
                    }`}>
                      {isArabic ? pro.specialty : pro.specialtyEn}
                    </span>
                    <h3 className="text-sm font-medium text-gray-900 mt-1 mb-0.5 line-clamp-1">{isArabic ? pro.name : pro.nameEn}</h3>
                    <div className="flex items-center gap-1 mb-1">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{pro.rating}</span>
                      <span className="text-xs text-gray-400">({pro.reviews})</span>
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {pro.location}</span>
                      <a href={`tel:${pro.phone}`} onClick={(e) => e.stopPropagation()} className={`hover:underline flex-shrink-0 ${isDoctor ? 'text-emerald-600' : 'text-violet-600'}`}>📞</a>
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
                  {filteredProfessionals.slice(0, 4).map((p, i) => (
                    <div key={i} className="overflow-hidden">
                      <img src={p.image} alt="" className="w-full h-full object-cover" />
                    </div>
                  ))}
                </div>
                <div className="absolute inset-0 bg-black/40 group-hover:bg-black/50 transition-colors" />
                <div className="absolute inset-0 flex flex-col items-center justify-center text-white">
                  <span className="text-base font-semibold">{isArabic ? 'عرض الكل' : 'Show all'}</span>
                  <span className="text-xs text-white/80 mt-0.5">{filteredProfessionals.length} {isArabic ? 'مهني' : 'pros'}</span>
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
              {isArabic ? 'جميع المهنيين' : 'All Professionals'}
            </Drawer.Title>
            <p className="text-sm text-gray-500 text-right">
              {filteredProfessionals.length} {isArabic ? 'مهني متاح' : 'pros available'}
            </p>
          </div>
          <div className="flex-1 overflow-y-auto px-4 py-4" style={{ maxHeight: 'calc(90vh - 100px)' }}>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
              {filteredProfessionals.map((pro) => {
                const isFavorite = favorites.includes(pro.id);
                const isDoctor = pro.category === 'doctor';
                return (
                  <div key={pro.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2">
                      <img src={pro.image} alt="" className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300" />
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(pro.id); }} className="absolute top-2 right-2 p-1.5">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      {pro.verified && (
                        <span className="absolute top-2 left-2 px-2 py-0.5 bg-blue-500/90 text-white text-[9px] font-semibold rounded-full backdrop-blur-sm flex items-center gap-0.5">
                          <Award className="w-2.5 h-2.5" /> {isArabic ? 'موثق' : 'Verified'}
                        </span>
                      )}
                    </div>
                    <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${isDoctor ? 'bg-emerald-100 text-emerald-700' : 'bg-violet-100 text-violet-700'}`}>
                      {isArabic ? pro.specialty : pro.specialtyEn}
                    </span>
                    <h3 className="text-sm font-medium text-gray-900 mt-1 mb-0.5 line-clamp-1">{isArabic ? pro.name : pro.nameEn}</h3>
                    <div className="flex items-center gap-1 mb-1">
                      <Star className="w-3 h-3 fill-gray-900 text-gray-900" />
                      <span className="text-xs font-medium">{pro.rating}</span>
                      <span className="text-xs text-gray-400">({pro.reviews})</span>
                    </div>
                    <div className="flex items-center justify-between text-xs text-gray-500">
                      <span className="truncate flex-1">📍 {pro.location}</span>
                      <a href={`tel:${pro.phone}`} onClick={(e) => e.stopPropagation()} className={`hover:underline flex-shrink-0 ${isDoctor ? 'text-emerald-600' : 'text-violet-600'}`}>📞</a>
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
